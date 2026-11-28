package br.com.urutau.jigs.ai;

import static br.com.urutau.jigs.quotes.QuoteDtos.MAX_ITEMS;
import static br.com.urutau.jigs.quotes.QuoteDtos.MAX_ITEM_QUANTITY;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import br.com.urutau.jigs.ai.AiDtos.ConversationView;
import br.com.urutau.jigs.ai.AiDtos.EditView;
import br.com.urutau.jigs.ai.AiDtos.Workspace;
import br.com.urutau.jigs.ai.AiDtos.ReviewItem;
import br.com.urutau.jigs.ai.AiInterpreter.Input;
import br.com.urutau.jigs.ai.AiInterpreter.Model;
import br.com.urutau.jigs.ai.AiInterpreter.ProposedItem;
import br.com.urutau.jigs.ai.AiInterpreter.Result;
import br.com.urutau.jigs.ai.AiInterpreter.Turn;
import br.com.urutau.jigs.ai.AiInterpreter.WeightOptions;
import br.com.urutau.jigs.catalog.CatalogService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.WebUtils;

@Service
class AiConversationService {
    private static final String SESSION_KEY = AiConversationService.class.getName();
    private static final int MAX_CONVERSATIONS = 4;
    private static final int MAX_HISTORY_TURNS = 8;
    private static final int MAX_PROVIDER_MESSAGE_LENGTH = 3000;
    private static final int MAX_QUESTIONS = 8;
    private static final int MAX_QUESTION_LENGTH = 250;

    private static final Pattern NOTE = Pattern.compile(
        "(?isu)^\\s*(?:(adicione|acrescente|inclua|informe|registre|altere|mude|substitua|defina)"
        + "\\s+(?:(?:uma|a)\\s+)?)?observa[çc][ãa]o\\s*"
        + "(?::\\s*|(?:informando(?:\\s+que)?|dizendo(?:\\s+que)?|para|com(?:\\s+o\\s+texto)?)\\s+)(\\S.*)$");

    private final AiInterpreter interpreter;
    private final CatalogService catalog;

    AiConversationService(AiInterpreter interpreter, CatalogService catalog) {
        this.interpreter = interpreter;
        this.catalog = catalog;
    }

    boolean configured() {
        return interpreter.configured();
    }

    ConversationView start(HttpSession session) {
        UUID id = UUID.randomUUID();
        var store = conversationStore(session);
        synchronized (store) {
            if (store.conversations.size() >= MAX_CONVERSATIONS) {
                store.conversations.pollFirstEntry();
            }
            store.conversations.put(id, new Conversation());
        }
        return new ConversationView(id, List.of(), List.of(), false);
    }

    ConversationView message(HttpSession session, UUID id, String message) {
        var conversation = find(session, id);
        var order = CatalogOrderParser.parse(message);
        if (order.isEmpty() && message.length() > MAX_PROVIDER_MESSAGE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Divida a mensagem livre em partes de até 3.000 caracteres.");
        }
        if (order.isEmpty() && !configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Configure o provedor da URUTAU AI para interpretar mensagens livres ou cole um pedido do catálogo.");
        }

        synchronized (conversation) {
            var snapshot = catalogSnapshot();
            if (snapshot.options().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cadastre pelo menos uma combinação ativa no catálogo para usar a URUTAU AI.");
            }
            var privacy = new MessagePrivacy(snapshot.models());
            String sanitized = privacy.forProvider(message, !conversation.turns.isEmpty());
            var input = new Input(snapshot.models(), List.copyOf(conversation.turns), conversation.draft, sanitized);
            ConversationView view = order.isPresent() ? interpretOrder(id, order.get(), snapshot, input,
                message.length() <= MAX_PROVIDER_MESSAGE_LENGTH)
                : interpretFreeText(id, input);
            remember(conversation, view, privacy, sanitized, order.isPresent());
            return view;
        }
    }

    EditView edit(HttpSession session, UUID id, String message, Workspace workspace) {
        find(session, id);
        return editDraft(id, message, workspace);
    }

    EditView editDraft(UUID id, String message, Workspace workspace) {
        var snapshot = catalogSnapshot();
        var byId = new LinkedHashMap<Long, Combination>();
        snapshot.options().forEach((combination, variantId) -> byId.put(variantId, combination));
        var unavailable = workspace.items().stream().map(item -> item.variantId()).filter(idValue -> !byId.containsKey(idValue)).toList();
        if (!unavailable.isEmpty()) {
            catalog.findVariants(unavailable).forEach((variantId, item) ->
                byId.put(variantId, new Combination(item.model(), item.weight(), item.color())));
        }
        var current = new LinkedHashMap<Long, ReviewItem>();
        for (var item : workspace.items()) {
            var variant = byId.get(item.variantId());
            if (variant == null || current.putIfAbsent(item.variantId(), new ReviewItem(item.variantId(),
                    variant.model(), variant.weight(), variant.color(), item.quantity())) != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "O rascunho contém uma combinação inválida ou indisponível. Confira o catálogo ou transfira o rascunho para o gerador manual.");
            }
        }
        var note = NOTE.matcher(message);
        if (note.matches()) {
            String verb = note.group(1);
            String text = note.group(2).strip();
            boolean append = verb != null && List.of("adicione", "acrescente", "inclua").contains(verb.toLowerCase(Locale.ROOT));
            String updated = append && !workspace.notes().isBlank() ? workspace.notes() + "\n" + text : text;
            if (updated.length() > 1000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A observação comporta até 1.000 caracteres.");
            }
            return new EditView(id, List.copyOf(current.values()), updated, List.of(), "");
        }
        var privacy = new MessagePrivacy(snapshot.models());
        String sanitized = privacy.forProvider(message, workspace.previous() != null && !workspace.previous().isBlank());
        var order = CatalogOrderParser.parse(message).or(() -> CatalogOrderParser.parseAddition(message));
        if (order.isPresent()) {
            var local = validateCatalogOrder(id, order.get(), snapshot);
            if (local.needsClarification()) {
                return unresolved(id, current, workspace.notes(), local.questions(), sanitized);
            }
            for (var item : local.items()) {
                var existing = current.get(item.variantId());
                int total = item.quantity() + (existing == null ? 0 : existing.quantity());
                if (total > MAX_ITEM_QUANTITY) {
                    return unresolved(id, current, workspace.notes(),
                        List.of("A quantidade acumulada ultrapassa o limite por combinação."), sanitized);
                }
                current.put(item.variantId(), new ReviewItem(item.variantId(), item.model(), item.weight(), item.color(), total));
            }
            return finishEdit(id, workspace, current, sanitized);
        }
        if (message.length() > MAX_PROVIDER_MESSAGE_LENGTH || sanitized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Envie uma instrução comercial de até 3.000 caracteres.");
        }
        if (!configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Configure o provedor da URUTAU AI para interpretar mensagens livres ou cole um pedido do catálogo.");
        }

        var history = workspace.previous() == null || workspace.previous().isBlank() ? List.<Turn>of()
            : List.of(new Turn(privacy.forProvider(workspace.previous(), true), workspace.questions() == null ? List.of()
                : workspace.questions().stream().map(question -> privacy.forProvider(question, true)).toList()));
        var baseline = current.values().stream().map(item ->
            new ProposedItem(item.model(), item.weight(), item.color(), item.quantity())).toList();
        var edits = interpreter.interpret(new Input(snapshot.models(), history, new Result(baseline, List.of(), false), sanitized, true));
        if (edits.needsClarification() || !edits.questions().isEmpty() || edits.items().isEmpty()) {
            var questions = edits.questions().stream().limit(MAX_QUESTIONS).map(MessagePrivacy::stripContactDetails)
                .filter(question -> !question.isBlank()).map(question -> limit(question, MAX_QUESTION_LENGTH)).toList();
            return unresolved(id, current, workspace.notes(),
                questions.isEmpty() ? List.of("Quais itens deseja incluir ou alterar?") : questions, sanitized);
        }

        var revised = catalogSnapshot();
        var changed = new LinkedHashMap<>(current);
        var touched = new LinkedHashSet<Long>();
        if (edits.items().size() > MAX_ITEMS) {
            return unresolved(id, current, workspace.notes(), List.of("Divida as alterações em comandos menores."), sanitized);
        }
        for (var item : edits.items()) {
            var variant = activeCombination(item, revised);
            Long variantId = variant == null ? null : revised.options().get(variant);
            if (variantId == null && item.quantity() == 0) {
                variantId = current.values().stream().filter(existing -> existing.model().equalsIgnoreCase(item.model())
                    && existing.weight() == item.weight() && existing.color().equalsIgnoreCase(item.color()))
                    .map(ReviewItem::variantId).findFirst().orElse(null);
            }
            if (variantId == null || !touched.add(variantId) || item.quantity() < 0 || item.quantity() > MAX_ITEM_QUANTITY
                    || (item.quantity() == 0 && !current.containsKey(variantId))) {
                return unresolved(id, current, workspace.notes(),
                    List.of("Confira as combinações e quantidades solicitadas. Nenhuma alteração foi aplicada."), sanitized);
            }
            if (item.quantity() == 0) {
                changed.remove(variantId);
            } else {
                changed.put(variantId, new ReviewItem(variantId, variant.model(), variant.weight(), variant.color(), item.quantity()));
            }
        }
        if (changed.keySet().stream().anyMatch(variant -> snapshot.options().containsValue(variant)
                && !revised.options().containsValue(variant))) {
            return unresolved(id, current, workspace.notes(),
                List.of("O catálogo mudou durante a interpretação. Confira os itens e tente novamente."), sanitized);
        }
        return finishEdit(id, workspace, changed, sanitized);
    }

    private static EditView finishEdit(UUID id, Workspace workspace, Map<Long, ReviewItem> items, String context) {
        if (items.size() > MAX_ITEMS) {
            return new EditView(id, List.of(), workspace.notes(),
                List.of("O orçamento aceita até " + MAX_ITEMS + " combinações."), limit(context, MAX_PROVIDER_MESSAGE_LENGTH));
        }
        return new EditView(id, List.copyOf(items.values()), workspace.notes(), List.of(), "");
    }

    private static EditView unresolved(UUID id, Map<Long, ReviewItem> current, String notes, List<String> questions, String context) {
        return new EditView(id, List.copyOf(current.values()), notes, questions.stream().limit(MAX_QUESTIONS)
            .map(question -> limit(question, MAX_QUESTION_LENGTH)).toList(), limit(context, MAX_PROVIDER_MESSAGE_LENGTH));
    }

    private static String limit(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }

    private ConversationView interpretFreeText(UUID id, Input input) {
        if (input.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Informe somente os modelos, as gramaturas, as cores e as quantidades do pedido.");
        }
        Result response = interpreter.interpret(input);
        return validate(id, response, catalogSnapshot().options());
    }

    private ConversationView interpretOrder(UUID id, CatalogOrderParser.Order order, CatalogSnapshot snapshot, Input input,
                                             boolean providerLengthAllowed) {
        if (!order.ambiguous() || !providerLengthAllowed || input.message().isBlank() || !configured()) {
            return validateCatalogOrder(id, order, snapshot);
        }

        Result suggestion;
        try {
            suggestion = interpreter.interpret(input);
        } catch (ResponseStatusException failure) {
            if (!failure.getStatusCode().is5xxServerError() && failure.getStatusCode().value() != 429) {
                throw failure;
            }
            var local = validateCatalogOrder(id, order, snapshot);
            var questions = new LinkedHashSet<>(local.questions());
            questions.add(failure.getReason() == null ? "Não foi possível interpretar o restante do pedido." : failure.getReason());
            return new ConversationView(id, local.items(), List.copyOf(questions), true);
        }
        var current = catalogSnapshot();
        var local = validateCatalogOrder(id, order, current);
        var inferred = validate(id, suggestion, current.options());
        return mergeSuggestions(local, inferred, order.declaredTotal());
    }

    private static ConversationView validateCatalogOrder(UUID id, CatalogOrderParser.Order order, CatalogSnapshot catalog) {
        var questions = new LinkedHashSet<>(order.questions());
        var quantities = new LinkedHashMap<Combination, Integer>();
        for (var row : order.rows()) {
            var combination = activeCombination(row.item(), catalog);
            if (combination == null) {
                questions.add("Corrija a combinação de modelo, gramatura e cor da linha " + row.line()
                    + ". Ela não está disponível no catálogo.");
                continue;
            }
            quantities.merge(combination, row.item().quantity(), Integer::sum);
        }

        var items = new ArrayList<ReviewItem>();
        for (var entry : quantities.entrySet()) {
            var combination = entry.getKey();
            int quantity = entry.getValue();
            if (quantity > MAX_ITEM_QUANTITY) {
                questions.add("A quantidade acumulada de " + combination.model() + " " + combination.weight()
                    + "g, cor " + combination.color() + ", ultrapassa o limite por item.");
                continue;
            }
            items.add(new ReviewItem(catalog.options().get(combination), combination.model(), combination.weight(),
                combination.color(), quantity));
        }
        return new ConversationView(id, List.copyOf(items), List.copyOf(questions), !questions.isEmpty());
    }

    private static Combination activeCombination(ProposedItem item, CatalogSnapshot catalog) {
        var model = catalog.models().stream().filter(candidate -> candidate.model().equalsIgnoreCase(item.model()))
            .findFirst().orElseGet(() -> {
                String withoutUnits = item.model().replaceFirst("(?iu)^(?:iscas?|unidades?|pe[çc]as?|un\\.?)\\h+(?:de\\h+)?", "");
                var matches = catalog.models().stream().filter(candidate -> candidate.model().equalsIgnoreCase(withoutUnits)).toList();
                return matches.size() == 1 ? matches.getFirst() : null;
            });
        if (model == null) {
            return null;
        }
        String color = item.color().toUpperCase(Locale.ROOT);
        var exact = new Combination(model.model(), item.weight(), color);
        if (catalog.options().containsKey(exact)) {
            return exact;
        }
        if (color.length() == 1 && color.charAt(0) >= '0' && color.charAt(0) <= '9') {
            var padded = new Combination(model.model(), item.weight(), "0" + color);
            if (catalog.options().containsKey(padded)) {
                return padded;
            }
        }
        return null;
    }

    private static ConversationView mergeSuggestions(ConversationView local, ConversationView inferred, Integer declaredTotal) {
        var items = new LinkedHashMap<Long, ReviewItem>();
        var questions = new LinkedHashSet<>(local.questions());
        local.items().forEach(item -> items.put(item.variantId(), item));
        inferred.items().forEach(item -> items.putIfAbsent(item.variantId(), item));
        if (questions.isEmpty()) {
            questions.addAll(inferred.questions());
        }
        if (items.size() > MAX_ITEMS) {
            questions.add("O orçamento aceita até " + MAX_ITEMS + " linhas. Quais itens devem ser mantidos?");
        }
        var limited = items.values().stream().limit(MAX_ITEMS).toList();
        int count = limited.stream().mapToInt(ReviewItem::quantity).sum();
        if (declaredTotal != null && declaredTotal != count) {
            questions.add("O total informado é " + declaredTotal + ", mas os itens identificados somam " + count
                + " unidades. Confira o pedido.");
        }
        return new ConversationView(local.id(), limited, List.copyOf(questions), !questions.isEmpty());
    }

    private static void remember(Conversation conversation, ConversationView view, MessagePrivacy privacy,
                                 String sanitized, boolean catalogOrder) {
        var validatedItems = view.items().stream()
            .map(item -> new ProposedItem(item.model(), item.weight(), item.color(), item.quantity())).toList();
        var contextQuestions = view.questions().stream().map(question -> privacy.forProvider(question, true))
            .filter(question -> !question.isBlank()).toList();
        String context = catalogOrder && !view.needsClarification() ? validatedItems.stream()
            .map(item -> "%s | %dg | Cor %s | Quantidade: %d".formatted(item.model(), item.weight(), item.color(), item.quantity()))
            .collect(Collectors.joining("\n")) : sanitized;
        if (context.length() > MAX_PROVIDER_MESSAGE_LENGTH) {
            context = "Pedido do catálogo com detalhes de itens no rascunho e pendências em questions.";
        }
        if (conversation.turns.size() == MAX_HISTORY_TURNS) {
            conversation.turns.removeFirst();
        }
        conversation.turns.addLast(new Turn(context, contextQuestions));
        conversation.draft = new Result(validatedItems, contextQuestions, view.needsClarification());
    }

    private Conversation find(HttpSession session, UUID id) {
        var store = conversationStore(session);
        synchronized (store) {
            var conversation = store.conversations.get(id);
            if (conversation == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Conversa indisponível. Inicie uma nova conversa.");
            }
            return conversation;
        }
    }

    private static ConversationStore conversationStore(HttpSession session) {
        synchronized (WebUtils.getSessionMutex(session)) {
            if (session.getAttribute(SESSION_KEY) instanceof ConversationStore store) {
                return store;
            }
            var store = new ConversationStore();
            session.setAttribute(SESSION_KEY, store);
            return store;
        }
    }

    private CatalogSnapshot catalogSnapshot() {
        var options = new LinkedHashMap<Combination, Long>();
        var models = new ArrayList<Model>();
        for (var product : catalog.list()) {
            if (!product.active()) {
                continue;
            }
            var weights = new LinkedHashMap<Integer, List<String>>();
            for (var variant : product.variants()) {
                if (variant.active()) {
                    weights.computeIfAbsent(variant.weight(), ignored -> new ArrayList<>()).add(variant.color());
                    options.put(new Combination(product.name(), variant.weight(), variant.color()), variant.id());
                }
            }
            if (!weights.isEmpty()) {
                var productOptions = weights.entrySet().stream()
                    .map(entry -> new WeightOptions(entry.getKey(), List.copyOf(entry.getValue())))
                    .toList();
                models.add(new Model(product.name(), productOptions));
            }
        }
        return new CatalogSnapshot(List.copyOf(models), Map.copyOf(options));
    }

    private static ConversationView validate(UUID id, Result response, Map<Combination, Long> options) {
        var questions = new LinkedHashSet<String>();
        response.questions().stream()
            .limit(MAX_QUESTIONS)
            .map(MessagePrivacy::stripContactDetails)
            .filter(question -> !question.isBlank())
            .map(question -> question.length() <= MAX_QUESTION_LENGTH ? question
                : question.substring(0, MAX_QUESTION_LENGTH).stripTrailing() + "…")
            .forEach(questions::add);

        boolean invalidItem = false;
        var items = new LinkedHashMap<Long, ReviewItem>();
        for (var item : response.items().stream().limit(MAX_ITEMS).toList()) {
            Long variantId = options.get(new Combination(item.model(), item.weight(), item.color()));
            if (variantId == null || item.quantity() < 1 || item.quantity() > MAX_ITEM_QUANTITY
                || items.containsKey(variantId)) {
                invalidItem = true;
                continue;
            }
            items.put(variantId, new ReviewItem(variantId, item.model(), item.weight(), item.color(), item.quantity()));
        }

        if (response.items().size() > MAX_ITEMS) {
            questions.add("O orçamento aceita até " + MAX_ITEMS + " linhas. Quais itens devem ser mantidos?");
        }
        if (invalidItem) {
            questions.add("Confirme os modelos, as gramaturas, os códigos de cor e as quantidades dos itens não reconhecidos.");
        }
        if (response.needsClarification() && questions.isEmpty()) {
            questions.add("Pode detalhar quais itens deseja incluir ou alterar?");
        }
        return new ConversationView(id, List.copyOf(items.values()), List.copyOf(questions), !questions.isEmpty());
    }

    private static final class ConversationStore {
        private final LinkedHashMap<UUID, Conversation> conversations = new LinkedHashMap<>();
    }

    private static final class Conversation {
        private final Deque<Turn> turns = new ArrayDeque<>();
        private Result draft = new Result(List.of(), List.of(), false);
    }

    private record Combination(String model, int weight, String color) {}

    private record CatalogSnapshot(List<Model> models, Map<Combination, Long> options) {}
}

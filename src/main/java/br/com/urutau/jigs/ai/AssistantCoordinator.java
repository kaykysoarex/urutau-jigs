package br.com.urutau.jigs.ai;

import static br.com.urutau.jigs.ai.AssistantTools.*;
import static br.com.urutau.jigs.ai.AssistantTools.Tool.*;
import static br.com.urutau.jigs.ai.AssistantLanguage.*;
import static br.com.urutau.jigs.ai.AssistantCatalog.normalize;

import br.com.urutau.jigs.quotes.AssistantCommercial;
import br.com.urutau.jigs.quotes.AssistantDrafts;
import br.com.urutau.jigs.quotes.QuoteDtos;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class AssistantCoordinator {
    private static final Set<String> SAFE_WORDS = Set.of("achar", "recuperar", "relacao", "relatorio", "obter", "calcular",
        "vendas", "ver", "meus", "vinculacao", "orcar", "prazo", "detalhar", "resumo", "producao", "entregas",
        "lista", "cliente", "clientes", "compras", "compradores", "cadastrados", "pedido", "pedidos", "orcamento", "orcamentos", "rascunho", "rascunhos",
        "isca", "iscas", "modelo", "modelos", "produto", "produtos", "combinacao", "combinacoes",
        "ativo", "inativo", "tabela", "precos", "preco", "configuracao", "lojista", "consumidor", "quantidade", "minima",
        "cores", "gramaturas", "pdf", "historico", "comercial", "documentos", "periodo", "indicadores", "quero", "quanto");
    private final AssistantCommercial commercial;
    private final AssistantDrafts drafts;
    private final AssistantCatalog catalog;
    private final AssistantIndicators indicators;
    private final AiConversationService quotesAi;
    private final AdministrativeAssistant guidance;
    private final GeminiInterpreter gemini;
    private final boolean geminiEnabled;

    AssistantCoordinator(AssistantCommercial commercial, AssistantDrafts drafts, AssistantCatalog catalog, AssistantIndicators indicators,
                         AiConversationService quotesAi, AdministrativeAssistant guidance, GeminiInterpreter gemini,
                         org.springframework.core.env.Environment environment) {
        this.commercial = commercial;
        this.drafts = drafts;
        this.catalog = catalog;
        this.indicators = indicators;
        this.quotesAi = quotesAi;
        this.guidance = guidance;
        this.gemini = gemini;
        String provider = environment.getProperty("urutau.ai.provider", "gemini").strip();
        this.geminiEnabled = provider.isBlank() || provider.equalsIgnoreCase("gemini");
    }

    static final class Context {
        Long customer;
        Long quote;
        UUID draft;
        String previous = "";
        List<String> questions = List.of();
        Tool awaiting;
        Long sourceQuote;
        String incompleteCatalog;
    }

    Reply answer(Actor actor, AdministrativeAssistant.Section section, String message, UUID activeDraft, Context context) {
        actor.name();
        String text = normalize(message);
        if (has(text, "ignore as", "ignore suas", "desconsidere", "execute sql", "execute codigo", "execute comando",
            "system prompt", "chave de api", "variavel de ambiente", "senha do administrador", "desative a seguranca",
            "sem pedir confirmacao")) {
            return new Reply("A assistente opera com ferramentas comerciais autorizadas e confirmação do administrador.");
        }
        Tool tool = classify(text, section);
        if (context.awaiting != null && (tool == null || context.incompleteCatalog != null
                && !has(text, "quais", "mostre", "cadastre", "crie", "altere", "desative", "reative"))) {
            tool = context.awaiting;
            if (context.incompleteCatalog != null) {
                message = context.incompleteCatalog + ", " + message;
                text = normalize(message);
            }
        } else if (tool != context.awaiting) {
            context.incompleteCatalog = null;
        }
        if ((tool == null || tool == PRODUCT_UPDATE) && (activeDraft != null || context.draft != null)
                && has(text, "adicione", "retire", "remova", "troque", "observa") && !has(text, "catalogo", "modelo chamado")
                && (tool == null || has(text, "orcamento", "rascunho") || !has(text, "todas as gramaturas", "cores atuais", "modelo", "gramatura"))
                && (section != AdministrativeAssistant.Section.CATALOG || has(text, "rascunho"))) tool = DRAFT_UPDATE;
        if (tool == null) {
            var projected = Arrays.stream(text.split(" ")).filter(SAFE_WORDS::contains).distinct().limit(24).toList();
            if (geminiEnabled && gemini.configured() && !projected.isEmpty() && !has(text, "como emitir", "como faco", "como posso")) {
                try {
                    var inferred = gemini.classifyAdministrativeIntent(String.join(" ", projected), section.name());
                    if (inferred != null && !inferred.write()) tool = inferred;
                } catch (ResponseStatusException failure) {
                    return new Reply(failure.getReason());
                }
            }
            if (tool == null) return new Reply(guidance.answer(section, message));
        }
        if (tool != context.awaiting) {
            context.previous = "";
            context.questions = List.of();
            context.awaiting = null;
            context.sourceQuote = null;
        }
        try {
            var result = perform(actor, tool, message, text, activeDraft, context);
            if (context.incompleteCatalog != null && (tool == PRODUCT_CREATE || tool == PRODUCT_UPDATE)) {
                context.incompleteCatalog = null;
                context.awaiting = null;
            }
            return result;
        } catch (ResponseStatusException problem) {
            if ((tool == PRODUCT_CREATE || tool == PRODUCT_UPDATE) && message.length() <= 1800
                    && problem.getReason() != null && has(problem.getReason(), "Informe nome, gramaturas", "Informe as gramaturas e as cores")) {
                context.incompleteCatalog = message;
                context.awaiting = tool;
            }
            return new Reply(problem.getReason() == null ? "A operação exige uma nova revisão." : problem.getReason());
        }
    }

    private Reply perform(Actor actor, Tool tool, String raw, String text, UUID activeDraft, Context context) {
        var number = recordNumber(raw);
        UUID targetDraft = uuid(raw);
        if (targetDraft == null) targetDraft = activeDraft == null ? context.draft : activeDraft;
        boolean byOrder = has(text, "pedido") && !has(text, "orcamento");
        var lookup = customer(raw);
        if (lookup == null && (tool == CUSTOMERS || tool == CUSTOMER_HISTORY)) lookup = phone(raw);
        return switch (tool) {
            case CUSTOMERS -> commercial.customers(actor, lookup == null ? "" : lookup,
                has(text, "inativ") ? Boolean.FALSE : has(text, " ativos") ? Boolean.TRUE : null);
            case CUSTOMER_HISTORY -> commercial.customerHistory(actor, lookup, context.customer, !has(text, "ultimo orcamento", "orcamentos"));
            case ORDERS, QUOTES -> {
                if (has(text, "atrasad")) yield indicators.production(actor, true);
                if (has(text, "clientes") && has(text, "producao")) yield indicators.production(actor, false);
                var status = has(text, "aguardando", "pendente", "nao foram confirmados") ? QuoteDtos.Status.PENDENTE
                    : has(text, "cancelad") ? QuoteDtos.Status.CANCELADO : has(text, "confirmad", "convertid") ? QuoteDtos.Status.CONFIRMADO : null;
                if (period(text) || !dates(raw).isEmpty() || has(text, "maior valor")) {
                    yield indicators.records(actor, indicators.period(text, dates(raw)), tool == ORDERS,
                        status == null ? "" : status.name(), has(text, "maior valor"));
                }
                Boolean delivered = has(text, "entregues", "entregas") ? Boolean.TRUE : has(text, "producao") ? Boolean.FALSE : null;
                yield commercial.records(actor, tool == ORDERS && status != QuoteDtos.Status.PENDENTE, status, delivered, lookup == null ? "" : lookup);
            }
            case RECORD -> commercial.record(actor, quoteId(actor, number, context.quote, byOrder));
            case DRAFTS -> drafts.list(actor);
            case DRAFT -> drafts.open(actor, targetDraft == null || has(text, "ultimo") ? drafts.latest(actor) : targetDraft);
            case CATALOG, PRICES -> catalog.list(actor, raw, has(text, "desativado", "inativo") ? Boolean.FALSE
                : has(text, "modelos ativos", "modelos disponiveis") ? Boolean.TRUE : null, tool == PRICES);
            case SETTINGS -> catalog.threshold(actor, null);
            case INDICATORS -> indicators.report(actor, has(text, "compar")
                ? indicators.period("", List.of()) : indicators.period(text, dates(raw)),
                has(text, "compar"), has(text, "mais vendid", "mais compr", "ranking"));
            case CUSTOMER_CREATE, CUSTOMER_EDIT, CUSTOMER_ACTIVATE -> commercial.customerChange(actor, tool, lookup, context.customer,
                tool == CUSTOMER_CREATE ? newCustomerName(raw) : tool == CUSTOMER_EDIT && has(text, "nome") ? renamed(raw) : null,
                tool == CUSTOMER_ACTIVATE ? null : phone(raw), !has(text, "desative", "desativar"));
            case CUSTOMER_LINK, ORDER_CONFIRM, ORDER_CANCEL, ORDER_DELIVER, ORDER_TERMS -> {
                if (tool == ORDER_CANCEL && number == null && has(text, "todos", "todas")) {
                    yield new Reply(guidance.answer(AdministrativeAssistant.Section.ORDERS, raw));
                }
                var reason = tool == ORDER_CANCEL ? textAfter(raw, "porque|motivo|justificativa")
                    : textAfter(raw, "observa[çc][ãa]o\\s*(?:para|com|:)");
                var deadline = dates(raw).stream().findFirst().orElse(null);
                Long link = tool == CUSTOMER_LINK ? commercial.customerId(lookup, context.customer) : null;
                yield commercial.orderChange(actor, tool, quoteId(actor, number, context.quote, byOrder), reason, deadline, link);
            }
            case DRAFT_DUPLICATE -> {
                if (context.sourceQuote != null || has(text, "mas adicione", "e adicione", "com mais")) {
                    var customer = commercial.customerId(lookup, context.customer);
                    var seed = drafts.fromHistory(actor, customer);
                    if (context.sourceQuote != null) unchanged(context.sourceQuote, seed.quote());
                    String extra = context.sourceQuote == null ? textAfter(raw, "mas\\s+|e\\s+(?=adicione)|com\\s+mais") : raw;
                    context.customer = customer;
                    context.sourceQuote = seed.quote();
                    yield draftEdit(actor, tool, extra, null, customer, null, context, seed);
                }
                Long source = number != null ? commercial.quoteId(actor, number, byOrder) : context.quote;
                if (number == null && (lookup != null || source == null || has(text, "ultimo pedido"))) {
                    source = drafts.fromHistory(actor, commercial.customerId(lookup, context.customer)).quote();
                }
                yield drafts.duplicate(actor, source);
            }
            case DRAFT_CREATE, DRAFT_UPDATE -> {
                UUID target = tool == DRAFT_CREATE ? null : targetDraft == null ? drafts.latest(actor) : targetDraft;
                Long customer = null;
                String name = null;
                if (lookup != null && (tool == DRAFT_CREATE || has(text, "vincule", "vincular"))) {
                    if (has(text, "novo cliente")) name = newCustomerName(raw);
                    else customer = commercial.customerId(lookup, context.customer);
                } else if (tool == context.awaiting) customer = context.customer;
                yield draftEdit(actor, tool, raw, target, customer, name, context, null);
            }
            case DRAFT_DISCARD -> drafts.discard(actor, targetDraft == null ? drafts.latest(actor) : targetDraft);
            case ISSUE -> drafts.issue(actor, targetDraft == null ? drafts.latest(actor) : targetDraft);
            case PRODUCT_CREATE -> {
                var base = raw.split("(?iu)\\bexceto\\b", 2)[0].strip();
                yield catalog.create(actor, productName(base), weights(base), colors(base),
                    amount(base, "consumidor\\s+final|varejo"), amount(base, "lojista"), !has(text, "inativo", "inativa"), exclusions(raw));
            }
            case PRODUCT_UPDATE -> {
                boolean add = has(text, "adicione", "acrescente", "cadastre");
                Boolean active = has(text, "desative", "desativar") ? Boolean.FALSE
                    : has(text, "reative", "reativar") ? Boolean.TRUE : null;
                String next = has(text, "nome") ? renamed(raw) : null;
                int cut = next == null ? -1 : raw.lastIndexOf(next);
                String source = cut < 0 ? raw : raw.substring(0, cut);
                var retail = add ? amount(raw, "consumidor\\s+final|varejo") : null;
                var wholesale = add ? amount(raw, "lojista") : null;
                if ((retail == null) != (wholesale == null)) throw invalid("Informe os dois preços iniciais das novas combinações.");
                var initialPrices = retail == null ? null : new br.com.urutau.jigs.catalog.CatalogDtos.InitialPrices(retail, wholesale);
                yield catalog.change(actor, source, next, active, weights(raw), colors(raw), add,
                    has(text, "todas as gramaturas", "todas gramaturas"),
                    has(text, "todas as cores atuais", "cores atuais", "todas as cores cadastradas"), initialPrices);
            }
            case PRICE_UPDATE -> {
                boolean wholesale = has(text, "lojista");
                boolean retail = has(text, "consumidor final", "varejo");
                if (wholesale == retail) throw invalid("Informe uma tabela por proposta: lojista ou consumidor final.");
                var price = amount(raw, null);
                if (price == null) price = amount(raw, wholesale ? "lojista" : "consumidor\\s+final|varejo");
                boolean reduce = has(text, "reduza", "reduzir");
                if (price != null && reduce) price = price.negate();
                yield catalog.price(actor, raw, wholesale, price, reduce || has(text, "aumente", "aumentar"), weights(raw), colors(raw));
            }
            case SETTINGS_UPDATE -> {
                String units = match(raw, "(?<![\\d.,+\\-])([0-9]{1,9})\\s*(?:iscas?|unidades?)?\\s*[.!]?$");
                if (units == null) throw invalid("Informe o novo mínimo em unidades.");
                yield catalog.threshold(actor, Integer.parseInt(units));
            }
        };
    }

    private Reply draftEdit(Actor actor, Tool tool, String raw, UUID target, Long customer, String name,
                            Context context, AssistantDrafts.Seed seed) {
        var basis = seed == null ? drafts.basis(actor, target) : new AssistantDrafts.Basis(null, null, seed.items(), seed.notes());
        var text = normalize(raw);
        if (has(text, "transfira", "transferir")) {
            if (!has(text, "manual", "ia")) throw invalid("Informe o gerador de destino.");
            return drafts.write(actor, basis, basis.items(), basis.notes(), customer, name, !has(text, "manual"));
        }
        if (has(text, "vincule", "vincular") && customer != null || has(text, "rascunho vazio", "sem itens")) {
            return drafts.write(actor, basis, basis.items(), basis.notes(), customer, name, null);
        }
        String instruction = raw.replaceFirst("(?iu)^\\s*(?:crie|fa[çc]a|monte|prepare|gere)\\s+um\\s+or[çc]amento\\s*(?:de|com|:)?\\s*", "");
        if (customer != null || name != null) instruction = instruction.replaceFirst("(?iu)\\s+para\\s+[^\\n]+$", "").strip();
        if (instruction.isBlank()) return drafts.write(actor, basis, basis.items(), basis.notes(), customer, name, null);
        if (tool == DRAFT_CREATE && instruction.matches("(?s)^\\d+.+") && !instruction.contains("|")) instruction = "Adicione " + instruction;
        if (target != null) context.draft = target;
        if (customer != null) context.customer = customer;
        var interpreted = quotesAi.editDraft(UUID.randomUUID(), instruction,
            new AiDtos.Workspace(basis.items(), basis.notes(), context.previous, context.questions));
        if (!interpreted.questions().isEmpty()) {
            context.previous = interpreted.previous();
            context.questions = interpreted.questions();
            context.awaiting = tool;
            return new Reply("Preciso esclarecer os itens antes de preparar o rascunho.",
                List.of(new Card("Pendências", interpreted.questions())));
        }
        context.awaiting = null;
        context.previous = "";
        context.questions = List.of();
        context.sourceQuote = null;
        var items = interpreted.items().stream().map(item -> new QuoteDtos.ItemInput(item.variantId(), item.quantity())).toList();
        return drafts.write(actor, basis, items, interpreted.notes(), customer, name, null);
    }

    private long quoteId(Actor actor, Long number, Long context, boolean order) {
        if (number != null) return commercial.quoteId(actor, number, order);
        if (context != null) return context;
        throw invalid("Informe o número do orçamento ou do pedido.");
    }
}

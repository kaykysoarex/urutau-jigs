
package br.com.urutau.jigs.quotes;

import br.com.urutau.jigs.catalog.CatalogService;
import br.com.urutau.jigs.catalog.CatalogDtos.VariantForQuote;
import br.com.urutau.jigs.quotes.QuoteDraftDtos.Content;
import br.com.urutau.jigs.quotes.QuoteDraftDtos.Listed;
import br.com.urutau.jigs.quotes.QuoteDraftDtos.Opened;
import br.com.urutau.jigs.quotes.QuoteDraftDtos.Save;
import br.com.urutau.jigs.quotes.QuoteDraftDtos.Variant;
import br.com.urutau.jigs.quotes.QuoteDtos.ItemInput;
import br.com.urutau.jigs.quotes.QuoteDtos.Preview;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
class QuoteDraftService {
    private final QuoteDraftRepository drafts;
    private final CatalogService catalog;
    private final QuoteService quotes;
    private final CustomerService customers;
    private final Clock clock;
    private final JsonMapper json;

    QuoteDraftService(QuoteDraftRepository drafts, CatalogService catalog, QuoteService quotes, CustomerService customers, Clock clock,
                      JsonMapper json) {
        this.drafts = drafts;
        this.catalog = catalog;
        this.quotes = quotes;
        this.customers = customers;
        this.clock = clock;
        this.json = json;
    }

    @Transactional(readOnly = true)
    List<Listed> list(String owner) {
        return drafts.findByOwnerAndContentIsNotNullOrderByUpdatedAtDescIdDesc(owner).stream()
                .map(draft -> summary(draft, content(draft))).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Opened open(UUID id, String owner) {
        var draft = drafts.findByIdAndOwnerAndContentIsNotNull(id.toString(), owner)
                .orElseThrow(QuoteDraftService::notFound);
        var content = content(draft);
        var variants = catalog.findVariants(variantIds(content));
        var estimate = estimate(content, variants);
        boolean changed = draft.estimateToken != null && estimate != null
                && !draft.estimateToken.equals(estimate.reviewToken());
        boolean editorUnavailable = editorModelUnavailable(content)
                || content.editor().colors().stream().anyMatch(color -> !active(variants, color.variantId()));
        return new Opened(summary(draft, content), content, variants.values().stream()
                .map(variant -> new Variant(variant.id(), variant.model(), variant.weight(), variant.color(), variant.active()))
                .toList(), estimate, changed, editorUnavailable,
                content.customer() == null ? null : customers.snapshot(content.customer().id()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    Listed save(UUID id, String owner, Save request) {
        String key = id.toString();
        var content = new Content(QuoteService.clean(request.content().client()), request.content().items(),
                request.content().notes(), request.content().editor(), request.content().pendingAi(), request.content().customer(), request.content().source(), request.content().ai());
        if (content.customer() != null && customers.snapshot(content.customer().id()).version() < content.customer().version()) {
            throw invalid("O vínculo do cliente no rascunho é inválido.");
        }
        String payload = json.writeValueAsString(content);
        if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65_535) {
            throw invalid("O rascunho está muito grande. Encurte as mensagens em edição.");
        }
        var draft = drafts.lockOwned(key, owner).orElse(null);
        if (draft == null) {
            if (request.version() != null) throw notFound();
            if (!content.meaningful()) throw invalid("Inclua informações antes de criar um rascunho.");
            draft = new QuoteDraft(key, owner, clock.instant());
        } else {
            // An identical retry can follow a response lost after commit.
            if (payload.equals(draft.content)) return summary(draft, content);
            draft.requireVersion(request.version());
        }

        requireDistinctItems(content.items());
        requireDistinctItems(content.pendingAi());
        var ids = variantIds(content);
        var variants = catalog.findVariants(ids);
        if (variants.size() != ids.size()) throw invalid("O rascunho contém combinações inexistentes.");
        validateEditor(content, variants);
        draft.store(payload, estimate(content, variants), clock.instant());
        return summary(drafts.saveAndFlush(draft), content);
    }

    @Transactional(readOnly = true)
    Listed retryCreation(UUID id, String owner, Save request) {
        var draft = drafts.findByIdAndOwnerAndContentIsNotNull(id.toString(), owner)
                .orElseThrow(QuoteDraftService::notFound);
        var stored = content(draft);
        var incoming = request.content();
        if (stored.equals(new Content(QuoteService.clean(incoming.client()), incoming.items(), incoming.notes(),
                incoming.editor(), incoming.pendingAi(), incoming.customer(), incoming.source(), incoming.ai()))) {
            return summary(draft, stored);
        }
        draft.requireVersion(request.version());
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Este rascunho foi alterado em outro dispositivo.");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    void discard(UUID id, String owner, long version) {
        var draft = drafts.lockOwned(id.toString(), owner).orElseThrow(QuoteDraftService::notFound);
        draft.requireVersion(version);
        draft.close(clock.instant());
    }

    private static void requireDistinctItems(List<ItemInput> items) {
        if (items.stream().mapToLong(ItemInput::variantId).distinct().count() != items.size()) {
            throw invalid("Informe uma linha por combinação de isca.");
        }
    }

    private void validateEditor(Content content, Map<Long, VariantForQuote> variants) {
        var editor = content.editor();
        if (editor.modelId() == null) {
            if (editor.weight() != null || !editor.colors().isEmpty()) {
                throw invalid("Informe o modelo das seleções pendentes.");
            }
        } else if (!editorMatchesCatalog(content, false)) {
            throw invalid("O modelo ou a gramatura pendente não existe no catálogo.");
        }
        if (editor.colors().stream().map(QuoteDraftDtos.Selection::variantId).distinct().count() != editor.colors().size()
                || editor.colors().stream().anyMatch(color -> {
                    var variant = variants.get(color.variantId());
                    return !Objects.equals(editor.modelId(), variant.productId())
                            || !Objects.equals(editor.weight(), variant.weight());
                })
                || (editor.editingId() != null && content.items().stream().noneMatch(
                        item -> item.variantId() == editor.editingId()))) {
            throw invalid("As seleções pendentes não correspondem ao orçamento.");
        }
    }

    private boolean editorModelUnavailable(Content content) {
        return content.editor().modelId() != null && !editorMatchesCatalog(content, true);
    }

    private boolean editorMatchesCatalog(Content content, boolean onlyActive) {
        var editor = content.editor();
        return catalog.list().stream().anyMatch(product -> product.id() == editor.modelId()
                && (!onlyActive || product.active())
                && (editor.weight() == null || product.variants().stream().anyMatch(variant ->
                        variant.weight() == editor.weight() && (!onlyActive || variant.active()))));
    }

    private Preview estimate(Content content, Map<Long, VariantForQuote> variants) {
        return content.items().isEmpty() || content.items().stream().anyMatch(item -> !active(variants, item.variantId()))
                ? null : quotes.calculate(content.items(), variants);
    }

    private static boolean active(Map<Long, VariantForQuote> variants, long id) {
        var variant = variants.get(id);
        return variant != null && variant.active();
    }

    private static LinkedHashSet<Long> variantIds(Content content) {
        var ids = new LinkedHashSet<Long>();
        content.items().forEach(item -> ids.add(item.variantId()));
        content.pendingAi().forEach(item -> ids.add(item.variantId()));
        content.editor().colors().forEach(color -> ids.add(color.variantId()));
        if (content.editor().editingId() != null) ids.add(content.editor().editingId());
        return ids;
    }

    private Content content(QuoteDraft draft) {
        return json.readValue(draft.content, Content.class);
    }

    private static Listed summary(QuoteDraft draft, Content content) {
        var items = content.items().isEmpty() ? content.pendingAi() : content.items();
        return new Listed(UUID.fromString(draft.id), draft.version, content.client().name(),
                items.stream().mapToInt(ItemInput::quantity).sum(),
                (int) items.stream().mapToLong(ItemInput::variantId).distinct().count(),
                draft.estimatedTotal, draft.updatedAt, content.source());
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Rascunho não encontrado.");
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

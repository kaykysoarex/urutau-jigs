package br.com.urutau.jigs.quotes;

import br.com.urutau.jigs.catalog.CatalogService;
import br.com.urutau.jigs.catalog.CatalogDtos.VariantForQuote;
import br.com.urutau.jigs.pdf.QuotePdf;
import br.com.urutau.jigs.quotes.QuoteDtos.Client;
import br.com.urutau.jigs.quotes.QuoteDtos.History;
import br.com.urutau.jigs.quotes.QuoteDtos.IssueRequest;
import br.com.urutau.jigs.quotes.QuoteDtos.ItemInput;
import br.com.urutau.jigs.quotes.QuoteDtos.Line;
import br.com.urutau.jigs.quotes.QuoteDtos.PdfPreviewRequest;
import br.com.urutau.jigs.quotes.QuoteDtos.Preview;
import br.com.urutau.jigs.quotes.QuoteDtos.PriceTier;
import br.com.urutau.jigs.quotes.QuoteDtos.ShareDetails;
import br.com.urutau.jigs.quotes.QuoteDtos.Summary;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class QuoteService {
    private static final ZoneId COMMERCIAL_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Set<String> BRAZILIAN_STATES = Set.of("AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO",
            "MA", "MT", "MS", "MG", "PA", "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");

    private final CatalogService catalog;
    private final QuoteRepository quotes;
    private final QuoteDraftRepository drafts;
    private final Clock clock;
    private final JsonMapper json;

    QuoteService(CatalogService catalog, QuoteRepository quotes, QuoteDraftRepository drafts, Clock clock, JsonMapper json) {
        this.catalog = catalog;
        this.quotes = quotes;
        this.drafts = drafts;
        this.clock = clock;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(List<ItemInput> items) {
        return calculate(items);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    QuotePdf.Preview pdfPreview(PdfPreviewRequest request) {
        var client = clean(request.client());
        var preview = calculate(request.items());
        var lines = preview.items().stream().map(line -> new QuotePdf.Line(
                line.model(), line.weight(), line.color(), line.quantity(), line.unitPrice(), line.subtotal())).toList();
        return new QuotePdf.Preview(LocalDate.ofInstant(clock.instant(), COMMERCIAL_ZONE),
                new QuotePdf.Client(client.name(), client.phone(), client.city(), client.state()),
                lines, preview.quantity(), preview.total());
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    Summary issue(IssueRequest request, String admin) {
        if ((request.draftId() == null) != (request.draftVersion() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o rascunho e sua versão.");
        }
        var draft = request.draftId() == null ? null : drafts.lockOwned(request.draftId().toString(), admin)
                .orElseThrow(QuoteDraftService::notFound);
        var existing = findIssued(admin, request.requestId());
        if (existing.isPresent()) {
            return existing.get();
        }

        var client = clean(request.client());
        if (draft != null) {
            draft.requireVersion(request.draftVersion());
            var saved = json.readValue(draft.content, QuoteDraftDtos.Content.class);
            if (!saved.client().equals(client) || !saved.items().equals(request.items())) {
                throw conflict("Sincronize as alterações do rascunho antes de emitir.");
            }
        }
        var preview = calculate(request.items());
        if (preview.missingPrices() > 0) {
            throw conflict("Cadastre todos os preços da tabela aplicada antes de emitir.");
        }
        if (!preview.reviewToken().equals(request.reviewToken())) {
            throw conflict("O catálogo ou os valores mudaram. Confira o orçamento atualizado antes de emitir.");
        }

        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var quote = new CommercialQuote(request.requestId().toString(), admin, now,
                LocalDate.ofInstant(now, COMMERCIAL_ZONE), client, preview);
        if (draft != null) draft.close(now);
        return quotes.saveAndFlush(quote).summary();
    }

    @Transactional(readOnly = true)
    Optional<Summary> findIssued(String admin, UUID requestId) {
        return quotes.findByCreatedByAndRequestId(admin, requestId.toString()).map(CommercialQuote::summary);
    }

    @Transactional(readOnly = true)
    History history(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe uma página e um tamanho válidos.");
        }
        var result = quotes.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        return new History(result.getContent().stream().map(CommercialQuote::summary).toList(),
                result.getNumber(), result.getTotalPages(), result.getTotalElements());
    }

    @Transactional(readOnly = true)
    QuotePdf.Document document(long number) {
        return issuedQuote(number).document();
    }

    @Transactional(readOnly = true)
    ShareDetails shareDetails(long number) {
        return issuedQuote(number).shareDetails();
    }

    private CommercialQuote issuedQuote(long number) {
        return quotes.findById(number).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Orçamento não encontrado."));
    }

    private Preview calculate(List<ItemInput> requested) {
        return calculate(requested, null);
    }

    Preview calculate(List<ItemInput> requested, Map<Long, VariantForQuote> knownVariants) {
        var grouped = new LinkedHashMap<Long, Integer>();
        int totalQuantity = 0;
        for (var item : requested) {
            if (item == null || item.variantId() < 1 || item.quantity() <= 0 || item.quantity() > QuoteDtos.MAX_ITEM_QUANTITY) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe uma variante e quantidade válidas.");
            }
            grouped.merge(item.variantId(), item.quantity(), Integer::sum);
            totalQuantity += item.quantity();
        }

        int threshold = catalog.wholesaleThreshold();
        PriceTier tier = totalQuantity >= threshold ? PriceTier.LOJISTA : PriceTier.CONSUMIDOR_FINAL;
        var variants = knownVariants == null ? catalog.findVariants(grouped.keySet()) : knownVariants;
        List<Line> lines = new ArrayList<>(grouped.size());
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        int missing = 0;
        for (var item : grouped.entrySet()) {
            var variant = variants.get(item.getKey());
            if (variant == null || !variant.active()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Uma combinação de modelo, gramatura e cor está indisponível. Confira os itens.");
            }
            var price = tier == PriceTier.LOJISTA ? variant.wholesalePrice() : variant.retailPrice();
            BigDecimal subtotal = null;
            if (price == null) {
                missing++;
            } else {
                subtotal = price.multiply(BigDecimal.valueOf(item.getValue()));
                total = total.add(subtotal);
            }
            lines.add(new Line(variant.id(), variant.model(), variant.weight(), variant.color(), item.getValue(), price, subtotal));
        }
        return new Preview(totalQuantity, tier, threshold, Math.max(0, threshold - totalQuantity),
                missing == 0 ? total : null, missing, List.copyOf(lines), reviewToken(tier, threshold, lines));
    }

    private String reviewToken(PriceTier tier, int threshold, List<Line> lines) {
        try {
            byte[] reviewed = json.writeValueAsBytes(List.of(tier, threshold, lines));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(reviewed));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        }
    }

    static Client clean(Client client) {
        if (client == null) {
            return new Client(null, null, null, null);
        }
        String state = optional(client.state());
        if (state != null) {
            state = state.toUpperCase(Locale.ROOT);
            if (!BRAZILIAN_STATES.contains(state)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selecione uma UF brasileira válida.");
            }
        }
        return new Client(optional(client.name()), optional(client.phone()), optional(client.city()), state);
    }

    private static String optional(String input) {
        return input == null || input.isBlank() ? null : cleanText(input);
    }

    private static String cleanText(String input) {
        String value = input.strip();
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Remova caracteres de controle dos dados do cliente.");
        }
        return value;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}

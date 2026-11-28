package br.com.urutau.jigs.catalog;

import br.com.urutau.jigs.config.OperationAudit;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import br.com.urutau.jigs.catalog.CatalogDtos.PricesUpdate;
import br.com.urutau.jigs.catalog.CatalogDtos.ProductUpdate;
import br.com.urutau.jigs.catalog.CatalogDtos.ProductView;
import br.com.urutau.jigs.catalog.CatalogDtos.Settings;
import br.com.urutau.jigs.catalog.CatalogDtos.VariantForQuote;
import br.com.urutau.jigs.catalog.CatalogDtos.InitialPrices;
import br.com.urutau.jigs.catalog.CatalogDtos.PriceChange;
import br.com.urutau.jigs.catalog.CatalogDtos.WeightPriceUpdate;
import br.com.urutau.jigs.catalog.CatalogDtos.WeightPrices;
import br.com.urutau.jigs.catalog.CatalogDtos.WeightPricesUpdate;
import br.com.urutau.jigs.catalog.CatalogDtos.VariantUpdate;
import br.com.urutau.jigs.catalog.CatalogDtos.VariantView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class CatalogService {
    private final CatalogProductRepository products;
    private final CatalogVariantRepository variants;
    private final CommercialSettingsRepository settings;
    private final Clock clock;
    private final OperationAudit audit;

    CatalogService(CatalogProductRepository products, CatalogVariantRepository variants,
                   CommercialSettingsRepository settings, Clock clock, OperationAudit audit) {
        this.products = products;
        this.variants = variants;
        this.settings = settings;
        this.clock = clock;
        this.audit = audit;
    }

    public List<ProductView> list() {
        return products.listWithVariants().stream().map(CatalogService::productView).toList();
    }

    public Map<Long, VariantForQuote> findVariants(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<Integer, WeightPrices>> prices = new LinkedHashMap<>();
        return variants.findWithProducts(ids).stream()
            .map(variant -> {
                var product = variant.product();
                var weights = prices.computeIfAbsent(product.id(), ignored -> weightIndex(product));
                var price = weights.get(variant.weight());
                return new VariantForQuote(variant.id(), product.id(), product.name(), variant.weight(), variant.color(),
                    product.active() && variant.active(), price.retailPrice(), price.wholesalePrice());
            })
            .collect(Collectors.toUnmodifiableMap(VariantForQuote::id, Function.identity()));
    }

    public int wholesaleThreshold() {
        return settingsRow().wholesaleThreshold();
    }

    @Transactional
    public ProductView create(ProductUpdate request) {
        return create(request, null);
    }

    @Transactional
    public ProductView create(ProductUpdate request, String admin) {
        requireUniqueName(request.name(), null);
        var product = new CatalogProduct(request.name(), request.active());
        product.createdAt = product.updatedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (request.prices() != null) {
            product.defaultRetailPrice = request.prices().retailPrice();
            product.defaultWholesalePrice = request.prices().wholesalePrice();
        }
        applyVariants(product, request.variants(), Map.of(), null);
        var created = productView(products.saveAndFlush(product));
        audit(admin, "PRODUCT_CREATE", Long.toString(created.id()), "", created.toString());
        return created;
    }

    @Transactional
    public ProductView update(long id, ProductUpdate request) {
        return update(id, request, null, null);
    }

    @Transactional
    public ProductView update(long id, ProductUpdate request, ProductView expected, String admin) {
        var product = products.findForUpdate(id).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Modelo de isca não encontrado."));
        var previous = productView(product);
        if (expected != null && !expected.equals(previous) || request.expectedVersion() != null
                && request.expectedVersion() != previous.version()) throw changed();
        requireUniqueName(request.name(), id);
        product.update(request.name(), request.active());
        touch(product);
        applyVariants(product, request.variants(), weightIndex(product), request.prices());
        products.flush();
        var changed = productView(product);
        audit(admin, "PRODUCT_UPDATE", Long.toString(id), previous.toString(), changed.toString());
        return changed;
    }

    @Transactional
    public void updatePrices(PricesUpdate request) {
        updatePrices(request, null);
    }

    @Transactional
    public void updatePrices(PricesUpdate request, String admin) {
        var ids = request.variants().stream().map(CatalogDtos.VariantPriceUpdate::id).toList();
        if (ids.isEmpty()) return;
        if (ids.size() != new java.util.HashSet<>(ids).size()) {
            throw badRequest("Há mais de um preço para a mesma combinação.");
        }
        var affected = lockProducts(variants.affectedModels(ids));
        var rows = variants.lockForModels(affected.keySet());
        var indexed = rows.stream().collect(Collectors.toMap(CatalogVariant::id, Function.identity()));
        Map<Long, Map<Integer, PriceChange>> changes = new LinkedHashMap<>();
        for (var update : request.variants()) {
            var row = indexed.get(update.id());
            if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Uma das combinações de isca não foi encontrada.");
            var price = new PriceChange(true, normalized(update.retailPrice()), true, normalized(update.wholesalePrice()));
            var previous = changes.computeIfAbsent(row.product().id(), ignored -> new LinkedHashMap<>())
                .putIfAbsent(row.weight(), price);
            if (previous != null && !previous.equals(price)) throw badRequest("Envie um único par de preços por gramatura.");
        }
        affected.forEach((id, product) -> writePrices(product, rowsFor(rows, id), changes.get(id), null, admin));
    }

    @Transactional
    public void updateWeightPrices(WeightPricesUpdate request, String admin) {
        var ids = request.models().stream().map(CatalogDtos.ModelPriceUpdate::id).toList();
        var affected = lockProducts(ids);
        var rows = variants.lockForModels(affected.keySet());
        for (var update : request.models()) {
            var product = affected.get(update.id());
            if (product.version() != update.version()) throw changed();
            Map<Integer, PriceChange> changes = new LinkedHashMap<>();
            for (WeightPriceUpdate row : update.weights()) {
                if (changes.putIfAbsent(row.weight(), row.prices()) != null) throw badRequest("Há gramaturas repetidas.");
            }
            writePrices(product, rowsFor(rows, update.id()), changes, update.defaults(), admin);
        }
    }

    private Map<Long, CatalogProduct> lockProducts(Collection<Long> ids) {
        Map<Long, CatalogProduct> locked = new LinkedHashMap<>();
        for (long id : ids.stream().distinct().sorted().toList()) {
            locked.put(id, products.findForUpdate(id).orElseThrow(CatalogService::changed));
        }
        if (locked.size() != ids.size()) throw badRequest("Há modelos repetidos.");
        return locked;
    }

    private static List<CatalogVariant> rowsFor(List<CatalogVariant> rows, long productId) {
        return rows.stream().filter(row -> row.product().id() == productId).toList();
    }

    private void writePrices(CatalogProduct product, List<CatalogVariant> rows, Map<Integer, PriceChange> changes,
                             PriceChange defaults, String admin) {
        var registered = rows.stream().map(CatalogVariant::weight).collect(Collectors.toSet());
        if (!registered.containsAll(changes.keySet())) throw changed();
        if (defaults != null && (!registered.equals(changes.keySet()) || changes.values().stream().anyMatch(price ->
                defaults.setRetail() && (!price.setRetail() || !sameAmount(defaults.retailPrice(), price.retailPrice()))
                || defaults.setWholesale() && (!price.setWholesale() || !sameAmount(defaults.wholesalePrice(), price.wholesalePrice()))))) {
            throw badRequest("Aplique o preço padrão a todas as gramaturas do modelo.");
        }
        if (changes.values().stream().anyMatch(price -> !price.setRetail() && !price.setWholesale())) {
            throw badRequest("Informe pelo menos uma tabela comercial para cada gramatura.");
        }
        var impacted = rows.stream().filter(row -> changes.containsKey(row.weight())).toList();
        var before = priceSnapshot(product, impacted);
        if (defaults != null) {
            if (defaults.setRetail()) product.defaultRetailPrice = defaults.retailPrice();
            if (defaults.setWholesale()) product.defaultWholesalePrice = defaults.wholesalePrice();
        }
        for (var variant : impacted) {
            var change = changes.get(variant.weight());
            variant.setPrices(change.setRetail() ? change.retailPrice() : variant.retailPrice(),
                change.setWholesale() ? change.wholesalePrice() : variant.wholesalePrice());
        }
        touch(product);
        audit(admin, "PRICE_UPDATE", product.id() + " · " + impacted.size() + " combinações",
            before, priceSnapshot(product, impacted));
    }

    private static String priceSnapshot(CatalogProduct product, List<CatalogVariant> rows) {
        return product.name() + " · padrões " + product.defaultRetailPrice + " / " + product.defaultWholesalePrice
            + " · " + CatalogPricing.group(rows.stream().map(CatalogService::variantView).toList());
    }

    private void touch(CatalogProduct product) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        product.updatedAt = product.updatedAt != null && !now.isAfter(product.updatedAt)
            ? product.updatedAt.plus(1, ChronoUnit.MICROS) : now;
    }

    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        return Objects.equals(normalized(left), normalized(right));
    }

    private static BigDecimal normalized(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    @Transactional
    public Settings updateSettings(Settings request) {
        return updateSettings(request, null);
    }

    @Transactional
    public Settings updateSettings(Settings request, Integer expected) {
        var row = settings.lockSettings();
        if (expected != null && row.wholesaleThreshold() != expected) throw changed();
        row.setWholesaleThreshold(request.wholesaleThreshold());
        return request;
    }

    private void audit(String admin, String action, String id, String before, String after) {
        if (admin != null) audit.record(UUID.randomUUID(), admin, "PAINEL", action, "CATALOGO", id, before, after, "SUCESSO");
    }

    private static ResponseStatusException changed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "O catálogo mudou. Revise a proposta novamente.");
    }

    private CommercialSettings settingsRow() {
        return settings.getReferenceById(CommercialSettings.SINGLETON_ID);
    }

    private void requireUniqueName(String name, Long currentId) {
        if (products.findByNameKey(CatalogProduct.nameKey(name))
            .filter(existing -> !Objects.equals(existing.id(), currentId)).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um modelo com esse nome.");
        }
    }

    private static void applyVariants(CatalogProduct product, List<VariantUpdate> updates,
                                      Map<Integer, WeightPrices> previousWeights, InitialPrices newWeightPrices) {
        Map<VariantKey, Boolean> desired = new LinkedHashMap<>();
        var existing = product.variants().stream().map(variant -> new VariantKey(variant.weight(), variant.color()))
            .collect(Collectors.toSet());
        for (var update : updates) {
            var color = update.color().toUpperCase(Locale.ROOT);
            var key = new VariantKey(update.weight(), color);
            if (!existing.contains(key)) {
                if (color.matches("[0-9]")) {
                    key = new VariantKey(update.weight(), "0" + color);
                } else if (color.matches("0[0-9]")) {
                    var legacy = new VariantKey(update.weight(), color.substring(1));
                    if (existing.contains(legacy)) key = legacy;
                }
            }
            if (desired.putIfAbsent(key, update.active()) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Há combinações de gramatura e cor repetidas.");
            }
        }
        for (var variant : product.variants()) {
            Boolean active = desired.remove(new VariantKey(variant.weight(), variant.color()));
            variant.setActive(Boolean.TRUE.equals(active));
        }
        desired.forEach((key, active) -> {
            var previous = previousWeights.get(key.weight());
            if (previous != null && previous.divergent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Resolva os preços divergentes de " + key.weight()
                    + " g na tabela de preços antes de adicionar cores a essa gramatura.");
            }
            var retail = previous != null ? previous.retailPrice()
                : newWeightPrices != null ? newWeightPrices.retailPrice() : product.defaultRetailPrice;
            var wholesale = previous != null ? previous.wholesalePrice()
                : newWeightPrices != null ? newWeightPrices.wholesalePrice() : product.defaultWholesalePrice;
            product.addVariant(key.weight(), key.color(), active).setPrices(retail, wholesale);
        });
    }

    private static ProductView productView(CatalogProduct product) {
        var entries = product.variants().stream()
            .sorted(Comparator.comparingInt(CatalogVariant::weight).thenComparing(CatalogVariant::color))
            .map(CatalogService::variantView)
            .toList();
        return new ProductView(product.id(), product.name(), product.active(), entries, product.createdAt, product.updatedAt,
            product.version(), product.defaultRetailPrice, product.defaultWholesalePrice, CatalogPricing.group(entries));
    }

    private static Map<Integer, WeightPrices> weightIndex(CatalogProduct product) {
        return CatalogPricing.group(product.variants().stream().map(CatalogService::variantView).toList())
            .stream().collect(Collectors.toMap(WeightPrices::weight, Function.identity()));
    }

    private static VariantView variantView(CatalogVariant variant) {
        return new VariantView(variant.id(), variant.weight(), variant.color(), variant.active(),
            variant.retailPrice(), variant.wholesalePrice());
    }

    private record VariantKey(int weight, String color) {}
}

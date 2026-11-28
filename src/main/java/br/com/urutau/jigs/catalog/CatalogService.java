package br.com.urutau.jigs.catalog;

import br.com.urutau.jigs.config.OperationAudit;
import java.time.Clock;
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
import br.com.urutau.jigs.catalog.CatalogDtos.VariantPriceUpdate;
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
        return variants.findWithProducts(ids).stream()
            .map(CatalogService::variantForQuote)
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
        applyVariants(product, request.variants());
        if (request.prices() != null) {
            product.variants().forEach(variant -> variant.setPrices(request.prices().retailPrice(), request.prices().wholesalePrice()));
        }
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
        var previousKeys = product.variants().stream().map(v -> new VariantKey(v.weight(), v.color()))
            .collect(Collectors.toSet());
        requireUniqueName(request.name(), id);
        product.update(request.name(), request.active());
        product.updatedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        applyVariants(product, request.variants());
        if (request.prices() != null) {
            product.variants().stream().filter(v -> !previousKeys.contains(new VariantKey(v.weight(), v.color())))
                .forEach(v -> v.setPrices(request.prices().retailPrice(), request.prices().wholesalePrice()));
        }
        products.flush();
        var changed = productView(product);
        audit(admin, "PRODUCT_UPDATE", Long.toString(id), previous.toString(), changed.toString());
        return changed;
    }

    @Transactional
    public void updatePrices(PricesUpdate request) {
        updatePrices(request, null, null);
    }

    @Transactional
    public void updatePrices(PricesUpdate request, Map<Long, VariantPriceUpdate> expected, String admin) {
        updatePrices(request, expected, List.of(), admin);
    }

    @Transactional
    public void updatePrices(PricesUpdate request, Map<Long, VariantPriceUpdate> expected,
                             List<ProductView> expectedModels, String admin) {
        Map<Long, VariantPriceUpdate> updates = new LinkedHashMap<>();
        for (var update : request.variants()) {
            if (updates.putIfAbsent(update.id(), update) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Há mais de um preço para a mesma combinação.");
            }
        }
        if (updates.isEmpty()) {
            return;
        }
        var affected = variants.affectedModels(updates.keySet()).stream().sorted().map(id ->
            products.findForUpdate(id).orElseThrow(CatalogService::changed)).toList();
        if (!expectedModels.isEmpty() && affected.stream().anyMatch(product -> !expectedModels.contains(productView(product)))) {
            throw changed();
        }
        var matches = variants.lockVariants(updates.keySet());
        if (matches.size() != updates.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Uma das combinações de isca não foi encontrada.");
        }
        Map<Long, VariantPriceUpdate> previous = new LinkedHashMap<>();
        for (var variant : matches) {
            previous.put(variant.id(), new VariantPriceUpdate(variant.id(), variant.retailPrice(), variant.wholesalePrice()));
        }
        if (expected != null && !previous.equals(expected)) throw changed();
        var updatedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        affected.forEach(product -> product.updatedAt = updatedAt);
        for (var variant : matches) {
            var update = updates.get(variant.id());
            variant.setPrices(update.retailPrice(), update.wholesalePrice());
        }
        audit(admin, "PRICE_UPDATE", updates.size() + " combinações", previous.toString(), updates.toString());
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

    private static void applyVariants(CatalogProduct product, List<VariantUpdate> updates) {
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
        desired.forEach((key, active) -> product.addVariant(key.weight(), key.color(), active));
    }

    private static ProductView productView(CatalogProduct product) {
        var entries = product.variants().stream()
            .sorted(Comparator.comparingInt(CatalogVariant::weight).thenComparing(CatalogVariant::color))
            .map(variant -> new VariantView(variant.id(), variant.weight(), variant.color(), variant.active(),
                variant.retailPrice(), variant.wholesalePrice()))
            .toList();
        return new ProductView(product.id(), product.name(), product.active(), entries, product.createdAt, product.updatedAt, product.version());
    }

    private static VariantForQuote variantForQuote(CatalogVariant variant) {
        var product = variant.product();
        return new VariantForQuote(variant.id(), product.id(), product.name(), variant.weight(), variant.color(),
            product.active() && variant.active(), variant.retailPrice(), variant.wholesalePrice());
    }

    private record VariantKey(int weight, String color) {}
}

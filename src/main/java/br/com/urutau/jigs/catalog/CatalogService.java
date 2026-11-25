package br.com.urutau.jigs.catalog;

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

    CatalogService(CatalogProductRepository products, CatalogVariantRepository variants,
                   CommercialSettingsRepository settings) {
        this.products = products;
        this.variants = variants;
        this.settings = settings;
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
        requireUniqueName(request.name(), null);
        var product = new CatalogProduct(request.name(), request.active());
        applyVariants(product, request.variants());
        return productView(products.saveAndFlush(product));
    }

    @Transactional
    public ProductView update(long id, ProductUpdate request) {
        var product = products.findForUpdate(id).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Modelo de isca não encontrado."));
        requireUniqueName(request.name(), id);
        product.update(request.name(), request.active());
        applyVariants(product, request.variants());
        products.flush();
        return productView(product);
    }

    @Transactional
    public void updatePrices(PricesUpdate request) {
        Map<Long, VariantPriceUpdate> updates = new LinkedHashMap<>();
        for (var update : request.variants()) {
            if (updates.putIfAbsent(update.id(), update) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Há mais de um preço para a mesma combinação.");
            }
        }
        if (updates.isEmpty()) {
            return;
        }
        var matches = variants.findWithProducts(updates.keySet());
        if (matches.size() != updates.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Uma das combinações de isca não foi encontrada.");
        }
        for (var variant : matches) {
            var update = updates.get(variant.id());
            variant.setPrices(update.retailPrice(), update.wholesalePrice());
        }
    }

    @Transactional
    public Settings updateSettings(Settings request) {
        settingsRow().setWholesaleThreshold(request.wholesaleThreshold());
        return request;
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
        for (var update : updates) {
            var key = new VariantKey(update.weight(), update.color().toUpperCase(Locale.ROOT));
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
        return new ProductView(product.id(), product.name(), product.active(), entries);
    }

    private static VariantForQuote variantForQuote(CatalogVariant variant) {
        var product = variant.product();
        return new VariantForQuote(variant.id(), product.id(), product.name(), variant.weight(), variant.color(),
            product.active() && variant.active(), variant.retailPrice(), variant.wholesalePrice());
    }

    private record VariantKey(int weight, String color) {}
}

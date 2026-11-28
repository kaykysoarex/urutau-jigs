package br.com.urutau.jigs.catalog;

import br.com.urutau.jigs.catalog.CatalogDtos.VariantView;
import br.com.urutau.jigs.catalog.CatalogDtos.WeightPrices;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class CatalogPricing {
    private CatalogPricing() {}

    static List<WeightPrices> group(List<VariantView> variants) {
        return variants.stream().collect(Collectors.groupingBy(VariantView::weight, TreeMap::new, Collectors.toList()))
            .entrySet().stream().map(entry -> {
                var rows = entry.getValue();
                return new WeightPrices(entry.getKey(), rows.size(),
                    values(rows.stream().map(VariantView::retailPrice).toList()),
                    values(rows.stream().map(VariantView::wholesalePrice).toList()));
            }).toList();
    }

    static List<BigDecimal> values(List<BigDecimal> prices) {
        return prices.stream().map(value -> value == null ? null : value.setScale(2)).distinct()
            .sorted(Comparator.nullsFirst(Comparator.naturalOrder())).toList();
    }
}

package br.com.urutau.jigs.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import br.com.urutau.jigs.catalog.CatalogDtos.PricesUpdate;
import br.com.urutau.jigs.catalog.CatalogDtos.ProductUpdate;
import br.com.urutau.jigs.catalog.CatalogDtos.ProductView;
import br.com.urutau.jigs.catalog.CatalogDtos.Settings;
import br.com.urutau.jigs.catalog.CatalogDtos.VariantPriceUpdate;
import br.com.urutau.jigs.catalog.CatalogDtos.VariantUpdate;
import br.com.urutau.jigs.catalog.CatalogDtos.VariantView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:catalog-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "urutau.admin.primary.username=teste-a",
    "urutau.admin.primary.password=senha-teste-a-123",
    "urutau.admin.secondary.username=teste-b",
    "urutau.admin.secondary.password=senha-teste-b-456"
})
class CatalogIntegrationTest {
    @Autowired
    private CatalogService catalog;

    @Test
    void loadsVerifiedModelsAndNonSequentialSpinnerColorsWithPricesUnset() {
        var products = catalog.list().stream()
            .collect(Collectors.toMap(ProductView::name, Function.identity()));
        var expectedCounts = Map.ofEntries(
            Map.entry("Urunaja", 18), Map.entry("Urumax G1", 20), Map.entry("Urumax G2", 7),
            Map.entry("Jig-Bolinha", 6), Map.entry("Micro-Jig", 18), Map.entry("Spinner", 15),
            Map.entry("Jig Peixinho", 12), Map.entry("Jig Urutau", 4), Map.entry("Jig Articulado", 24)
        );
        expectedCounts.forEach((name, count) -> {
            assertThat(products.get(name)).isNotNull();
            assertThat(products.get(name).variants()).hasSize(count)
                .allMatch(variant -> variant.active() && variant.retailPrice() == null && variant.wholesalePrice() == null);
        });
        for (int grams : List.of(12, 14, 16)) {
            assertThat(products.get("Spinner").variants()).filteredOn(variant -> variant.weight() == grams)
                .extracting(VariantView::color).containsExactly("01", "03", "04", "06", "08");
        }
    }

    @Test
    void removesAndRestoresACombinationWithoutLosingItsIdentityOrPrice() {
        var original = catalog.create(new ProductUpdate("Modelo de teste reutilizável", true,
            List.of(new VariantUpdate(10, "a-1", true))));
        long originalId = original.variants().getFirst().id();
        catalog.updatePrices(new PricesUpdate(List.of(
            new VariantPriceUpdate(originalId, new BigDecimal("20.00"), new BigDecimal("15.00"))
        )));

        var edited = catalog.update(original.id(), new ProductUpdate("Modelo de teste renomeado", true,
            List.of(new VariantUpdate(12, "B-2", true))));
        assertThat(edited.variants()).hasSize(2);
        assertThat(catalog.findVariants(List.of(originalId)).get(originalId).active()).isFalse();

        catalog.update(original.id(), new ProductUpdate(edited.name(), false,
            List.of(new VariantUpdate(10, "A-1", true))));
        var inactiveModel = catalog.findVariants(List.of(originalId)).get(originalId);
        assertThat(inactiveModel.active()).isFalse();
        assertThat(inactiveModel.wholesalePrice()).isEqualByComparingTo("15.00");

        var restored = catalog.update(original.id(), new ProductUpdate(edited.name(), true,
            List.of(new VariantUpdate(10, "A-1", true))));
        assertThat(restored.variants().getFirst().id()).isEqualTo(originalId);
        assertThat(catalog.findVariants(List.of(originalId)).get(originalId).active()).isTrue();
    }

    @Test
    void refusesInvalidPriceBatchAndSavesTheWholesaleThreshold() {
        var product = catalog.create(new ProductUpdate("Modelo de teste para preços", true,
            List.of(new VariantUpdate(10, "01", true))));
        long realId = product.variants().getFirst().id();
        assertThatThrownBy(() -> catalog.updatePrices(new PricesUpdate(List.of(
            new VariantPriceUpdate(realId, new BigDecimal("20.00"), null),
            new VariantPriceUpdate(Long.MAX_VALUE, new BigDecimal("20.00"), null)
        )))).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404");
        assertThat(catalog.findVariants(List.of(realId)).get(realId).retailPrice()).isNull();

        assertThat(catalog.wholesaleThreshold()).isEqualTo(30);
        catalog.updateSettings(new Settings(52));
        assertThat(catalog.wholesaleThreshold()).isEqualTo(52);
    }
}

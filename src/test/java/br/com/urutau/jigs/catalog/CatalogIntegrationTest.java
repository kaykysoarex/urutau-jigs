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
    void loadsVerifiedModelsAndNonSequentialSpinnerColorsWithInitialPrices() {
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
                .allMatch(variant -> variant.active() && variant.retailPrice() != null && variant.wholesalePrice() != null);
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

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Test
    void groupsAllColorsAndUsesEffectivePricesForQuotesAndNewVariants() {
        var model = catalog.create(new ProductUpdate("Modelo de preços por gramatura", true,
            List.of(color(10, "01"), color(10, "02"), color(14, "01")), initial("27", "17")));
        assertThat(model.weightPrices()).extracting(CatalogDtos.WeightPrices::combinations).containsExactly(2, 1);
        var other = get(catalog.create(new ProductUpdate("Outro modelo de preços por gramatura", true,
            List.of(color(10, "01")), initial("27", "17"))).id());

        var legacyVariant = model.variants().get(1);
        jdbc.sql("update catalog_variant set wholesale_price = null where id = :id").param("id", legacyVariant.id()).update();
        model = get(model.id());
        var conflict = model.weightPrices().getFirst();
        assertThat(conflict.wholesaleValues()).containsExactly(null, new BigDecimal("17.00"));
        assertThat(conflict.retailPrice()).isEqualByComparingTo("27.00");
        var groupIds = model.variants().stream().filter(row -> row.weight() == 10).map(VariantView::id).toList();
        assertThat(catalog.findVariants(groupIds).values()).allSatisfy(row -> {
            assertThat(row.wholesalePrice()).isNull();
            assertThat(row.retailPrice()).isEqualByComparingTo("27");
        });

        long id = model.id();
        var rows = List.of(color(10, "01"), color(10, "02"), color(10, "03"), color(14, "01"), color(18, "01"));
        assertThatThrownBy(() -> catalog.update(id, new ProductUpdate("Modelo de preços por gramatura", true, rows)))
            .hasMessageContaining("preços divergentes");
        var single = new CatalogDtos.WeightPriceUpdate(10, new CatalogDtos.PriceChange(false, null, true, new BigDecimal("18")));
        var saved = new CatalogDtos.ModelPriceUpdate(id, model.version(), null, List.of(single));
        catalog.updateWeightPrices(new CatalogDtos.WeightPricesUpdate(List.of(saved)), "admin-teste");
        assertThatThrownBy(() -> catalog.updateWeightPrices(new CatalogDtos.WeightPricesUpdate(List.of(saved)), "admin-teste"))
            .hasMessageContaining("mudou");

        model = catalog.update(id, new ProductUpdate(model.name(), true, rows));
        assertThat(model.variants()).filteredOn(row -> row.weight() == 10)
            .allSatisfy(row -> assertThat(row.wholesalePrice()).isEqualByComparingTo("18"));
        assertThat(model.weightPrices().getLast().wholesalePrice()).isEqualByComparingTo("17");
        var withNewWeight = new java.util.ArrayList<>(rows);
        withNewWeight.add(color(20, "02"));
        model = catalog.update(id, new ProductUpdate(model.name(), true, withNewWeight, initial("31", "21")));
        assertThat(model.weightPrices().getLast().wholesalePrice()).isEqualByComparingTo("21");

        var newDefault = new CatalogDtos.PriceChange(true, new BigDecimal("29"), true, new BigDecimal("19"));
        var all = model.weightPrices().stream().map(row -> new CatalogDtos.WeightPriceUpdate(row.weight(), newDefault)).toList();
        var bulk = new CatalogDtos.ModelPriceUpdate(id, model.version(), newDefault, all);
        var bad = new CatalogDtos.ModelPriceUpdate(other.id(), other.version(), null,
            List.of(new CatalogDtos.WeightPriceUpdate(99, newDefault)));
        assertThatThrownBy(() -> catalog.updateWeightPrices(new CatalogDtos.WeightPricesUpdate(List.of(bulk, bad)), "admin-teste"))
            .hasMessageContaining("mudou");
        assertThat(get(id).defaultWholesalePrice()).isEqualByComparingTo("17");
        assertThat(get(id).weightPrices().getFirst().wholesalePrice()).isEqualByComparingTo("18");
        catalog.updateWeightPrices(new CatalogDtos.WeightPricesUpdate(List.of(bulk)), "admin-teste");
        model = get(id);
        assertThat(model.defaultWholesalePrice()).isEqualByComparingTo("19");
        assertThat(model.variants()).allSatisfy(row -> {
            assertThat(row.retailPrice()).isEqualByComparingTo("29");
            assertThat(row.wholesalePrice()).isEqualByComparingTo("19");
        });
        assertThat(get(other.id())).isEqualTo(other);
        assertThat(jdbc.sql("select count(*) from assistant_audit where tool = 'PRICE_UPDATE' and actor = 'admin-teste'")
            .query(Long.class).single()).isEqualTo(2);
    }

    private ProductView get(long id) {
        return catalog.list().stream().filter(row -> row.id() == id).findFirst().orElseThrow();
    }

    private static CatalogDtos.InitialPrices initial(String retail, String wholesale) {
        return new CatalogDtos.InitialPrices(new BigDecimal(retail), new BigDecimal(wholesale));
    }

    private static VariantUpdate color(int grams, String code) {
        return new VariantUpdate(grams, code, true);
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

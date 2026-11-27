package br.com.urutau.jigs.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class OfficialPricesMigrationTest {
    private static final Map<String, OfficialPrice> OFFICIAL = Map.of(
        "urumax g1", official(20, "27.00", "16.00"),
        "urumax g2", official(7, "29.00", "18.00"),
        "urunaja", official(18, "27.00", "17.00"),
        "jig urutau", official(4, "26.00", "16.00"),
        "jig articulado", official(24, "26.00", "16.00"),
        "jig peixinho", official(12, "26.00", "16.00"),
        "micro-jig", official(18, "26.00", "16.00"),
        "jig-bolinha", official(6, "26.00", "16.00"),
        "spinner", official(15, "26.00", "16.00")
    );

    @Test
    void populatesBothOfficialPricesForEveryExistingCombination(@TempDir Path directory) {
        String url = database(directory);
        JdbcTemplate jdbc = jdbc(url);
        List<PricedVariant> before = variants(jdbc);
        var products = jdbc.queryForList("SELECT * FROM catalog_product ORDER BY id");
        assertThat(products).hasSize(9);
        assertThat(before).hasSize(124).allSatisfy(row -> {
            assertThat(row.retail()).isNull();
            assertThat(row.wholesale()).isNull();
        });

        assertThat(flyway(url).target("5").load().migrate().migrationsExecuted).isOne();

        List<PricedVariant> after = variants(jdbc);
        assertThat(jdbc.queryForList("SELECT * FROM catalog_product ORDER BY id")).isEqualTo(products);
        assertThat(after).extracting(PricedVariant::identity)
            .containsExactlyElementsOf(before.stream().map(PricedVariant::identity).toList());
        Map<String, Long> actualCounts = after.stream().collect(
            Collectors.groupingBy(row -> row.identity().key(), Collectors.counting()));
        assertThat(actualCounts).containsExactlyInAnyOrderEntriesOf(OFFICIAL.entrySet().stream().collect(
            Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().count())));
        assertThat(after).allSatisfy(row -> {
            OfficialPrice expected = OFFICIAL.get(row.identity().key());
            assertThat(row.retail()).as("consumidor final %s", row.identity()).isEqualByComparingTo(expected.retail());
            assertThat(row.wholesale()).as("lojista %s", row.identity()).isEqualByComparingTo(expected.wholesale());
        });
        assertThat(jdbc.queryForObject("SELECT wholesale_threshold FROM commercial_settings WHERE id = 1", Integer.class))
            .isEqualTo(30);
    }

    @Test
    void reportsDivergencesAndPreservesConfiguredPricesAcrossDeployments(@TempDir Path directory) {
        String url = database(directory);
        JdbcTemplate jdbc = jdbc(url);
        long g1 = variantId(jdbc, "urumax g1", 13, "01");
        long g2 = variantId(jdbc, "urumax g2", 16, "01");
        long urunaja = variantId(jdbc, "urunaja", 10, "01");
        long spinner = variantId(jdbc, "spinner", 16, "08");
        long inactive = variantId(jdbc, "jig-bolinha", 14, "03");
        updatePrices(jdbc, g1, "22.99", null);
        updatePrices(jdbc, g2, null, "0.00");
        updatePrices(jdbc, urunaja, "27.00", "17.00");
        updatePrices(jdbc, spinner, "42.12", "13.42");
        jdbc.update("UPDATE catalog_variant SET active = FALSE WHERE id = ?", inactive);
        jdbc.update("UPDATE commercial_settings SET wholesale_threshold = 42 WHERE id = 1");
        jdbc.update("""
            INSERT INTO catalog_product (name, name_key, active)
            VALUES ('Modelo personalizado', 'modelo personalizado', TRUE)
            """);
        jdbc.update("""
            INSERT INTO catalog_variant (product_id, weight_grams, color_code, active, wholesale_price)
            SELECT id, 7, 'AZ', TRUE, 21.05 FROM catalog_product WHERE name_key = 'modelo personalizado'
            """);
        long custom = variantId(jdbc, "modelo personalizado", 7, "AZ");
        var identities = variants(jdbc).stream().map(PricedVariant::identity).toList();

        var logger = (Logger) LoggerFactory.getLogger("db.migration.V5__Official_prices");
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            assertThat(flyway(url).target("5").load().migrate().migrationsExecuted).isOne();

            assertPrices(jdbc, g1, "22.99", "16.00");
            assertPrices(jdbc, g2, "29.00", "0.00");
            assertPrices(jdbc, urunaja, "27.00", "17.00");
            assertPrices(jdbc, spinner, "42.12", "13.42");
            assertPrices(jdbc, inactive, "26.00", "16.00");
            assertPrices(jdbc, custom, null, "21.05");
            assertThat(variants(jdbc)).extracting(PricedVariant::identity).containsExactlyElementsOf(identities);
            assertThat(jdbc.queryForObject("SELECT wholesale_threshold FROM commercial_settings WHERE id = 1", Integer.class))
                .isEqualTo(42);

            updatePrices(jdbc, g1, "31.59", "20.01");
            updatePrices(jdbc, urunaja, null, "23.00");
            assertThat(flyway(url).target("5").load().migrate().migrationsExecuted).isZero();
            JdbcTemplate afterRestart = jdbc(url);
            assertPrices(afterRestart, g1, "31.59", "20.01");
            assertPrices(afterRestart, urunaja, null, "23.00");
            assertThat(variants(afterRestart)).extracting(PricedVariant::identity).containsExactlyElementsOf(identities);
            assertThat(logs.list).filteredOn(log -> log.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactlyInAnyOrder(
                    "V5 preservou o preço de consumidor final para Urumax G1 (13 g, cor 01). Valor cadastrado R$ 22.99. Valor inicial oficial R$ 27.00.",
                    "V5 preservou o preço de lojista para Urumax G2 (16 g, cor 01). Valor cadastrado R$ 0.00. Valor inicial oficial R$ 18.00.",
                    "V5 preservou o preço de consumidor final para Spinner (16 g, cor 08). Valor cadastrado R$ 42.12. Valor inicial oficial R$ 26.00.",
                    "V5 preservou o preço de lojista para Spinner (16 g, cor 08). Valor cadastrado R$ 13.42. Valor inicial oficial R$ 16.00."
                );
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    private static String database(Path directory) {
        String database = directory.resolve("official-prices").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:h2:file:" + database + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        assertThat(flyway(url).target("4").load().migrate().migrationsExecuted).isEqualTo(4);
        return url;
    }

    private static FluentConfiguration flyway(String url) {
        return Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration");
    }

    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
    }

    private static List<PricedVariant> variants(JdbcTemplate jdbc) {
        return jdbc.query("""
            SELECT variant.id, variant.product_id, product.name_key, variant.weight_grams, variant.color_code,
                variant.active, variant.retail_price, variant.wholesale_price
            FROM catalog_variant variant JOIN catalog_product product ON variant.product_id = product.id
            ORDER BY variant.id
            """, (row, index) -> new PricedVariant(
            new VariantIdentity(row.getLong("id"), row.getLong("product_id"), row.getString("name_key"),
                row.getInt("weight_grams"), row.getString("color_code"), row.getBoolean("active")),
            row.getBigDecimal("retail_price"), row.getBigDecimal("wholesale_price")));
    }

    private static long variantId(JdbcTemplate jdbc, String key, int weight, String color) {
        return jdbc.queryForObject("""
            SELECT variant.id FROM catalog_variant variant JOIN catalog_product product ON variant.product_id = product.id
            WHERE product.name_key = ? AND variant.weight_grams = ? AND variant.color_code = ?
            """, Long.class, key, weight, color);
    }

    private static void updatePrices(JdbcTemplate jdbc, long id, String retail, String wholesale) {
        assertThat(jdbc.update("UPDATE catalog_variant SET retail_price = ?, wholesale_price = ? WHERE id = ?",
            decimal(retail), decimal(wholesale), id)).isOne();
    }

    private static void assertPrices(JdbcTemplate jdbc, long id, String retail, String wholesale) {
        jdbc.queryForObject("SELECT retail_price, wholesale_price FROM catalog_variant WHERE id = ?", (row, index) -> {
            assertThat(row.getBigDecimal("retail_price")).isEqualTo(decimal(retail));
            assertThat(row.getBigDecimal("wholesale_price")).isEqualTo(decimal(wholesale));
            return id;
        }, id);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static OfficialPrice official(long count, String retail, String wholesale) {
        return new OfficialPrice(count, decimal(retail), decimal(wholesale));
    }

    private record OfficialPrice(long count, BigDecimal retail, BigDecimal wholesale) {}
    private record PricedVariant(VariantIdentity identity, BigDecimal retail, BigDecimal wholesale) {}
    private record VariantIdentity(long id, long productId, String key, int weight, String color, boolean active) {}
}

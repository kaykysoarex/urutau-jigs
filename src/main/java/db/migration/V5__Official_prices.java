package db.migration;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class V5__Official_prices extends BaseJavaMigration {
    private static final Logger LOGGER = LoggerFactory.getLogger(V5__Official_prices.class);
    private static final Prices STANDARD_JIG = new Prices(26, 16);
    private static final Map<String, Prices> OFFICIAL_PRICES = Map.of(
        "urumax g1", new Prices(27, 16),
        "urumax g2", new Prices(29, 18),
        "urunaja", new Prices(27, 17),
        "jig urutau", STANDARD_JIG,
        "jig articulado", STANDARD_JIG,
        "jig peixinho", STANDARD_JIG,
        "micro-jig", STANDARD_JIG,
        "jig-bolinha", STANDARD_JIG,
        "spinner", STANDARD_JIG
    );

    @Override
    public void migrate(Context context) throws SQLException {
        var connection = context.getConnection();
        try (var variants = connection.prepareStatement("""
                 SELECT p.name, p.name_key, v.id, v.weight_grams, v.color_code,
                        v.retail_price, v.wholesale_price, v.version
                 FROM catalog_variant v
                 JOIN catalog_product p ON p.id = v.product_id
                 ORDER BY p.name_key, v.weight_grams, v.color_code
                 """);
             var fill = connection.prepareStatement("""
                 UPDATE catalog_variant
                 SET retail_price = COALESCE(retail_price, ?),
                     wholesale_price = COALESCE(wholesale_price, ?),
                     version = version + 1
                 WHERE id = ? AND version = ?
                     AND (retail_price IS NULL OR wholesale_price IS NULL)
                 """)) {
            try (var rows = variants.executeQuery()) {
                while (rows.next()) {
                    var official = OFFICIAL_PRICES.get(rows.getString("name_key"));
                    if (official == null) {
                        continue;
                    }
                    var retail = rows.getBigDecimal("retail_price");
                    var wholesale = rows.getBigDecimal("wholesale_price");
                    reportDivergence(rows, "consumidor final", retail, official.retail());
                    reportDivergence(rows, "lojista", wholesale, official.wholesale());
                    if (retail == null || wholesale == null) {
                        fill.setBigDecimal(1, official.retail());
                        fill.setBigDecimal(2, official.wholesale());
                        fill.setLong(3, rows.getLong("id"));
                        fill.setLong(4, rows.getLong("version"));
                        fill.addBatch();
                    }
                }
            }
            var changedDuringMigration = Arrays.stream(fill.executeBatch()).filter(updated -> updated == 0).count();
            if (changedDuringMigration > 0) {
                LOGGER.warn("A migração V5 preservou os preços atuais de {} combinações alteradas durante a execução.",
                    changedDuringMigration);
            }
        }
    }

    private static void reportDivergence(ResultSet row, String tier, BigDecimal current, BigDecimal official)
        throws SQLException {
        if (current != null && current.compareTo(official) != 0) {
            LOGGER.warn("V5 preservou o preço de {} para {} ({} g, cor {}). Valor cadastrado R$ {}. Valor inicial oficial R$ {}.",
                tier, row.getString("name"), row.getInt("weight_grams"), row.getString("color_code"),
                current.toPlainString(), official.toPlainString());
        }
    }

    private record Prices(BigDecimal retail, BigDecimal wholesale) {
        Prices(int retail, int wholesale) {
            this(BigDecimal.valueOf(retail).setScale(2), BigDecimal.valueOf(wholesale).setScale(2));
        }
    }
}

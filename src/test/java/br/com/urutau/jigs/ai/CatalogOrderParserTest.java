package br.com.urutau.jigs.ai;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.urutau.jigs.ai.AiInterpreter.ProposedItem;
import br.com.urutau.jigs.ai.CatalogOrderParser.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogOrderParserTest {
    private static final String ITEMS = """
        • Jig Peixinho | 12g | Cor 05 | Qtd: 5
        • Urunaja | 10g | Cor 01 | Qtd: 5
        • Urunaja | 10g | Cor 02 | Qtd: 5
        """;

    @Test
    void readsTheExactWhatsAppOrderGeneratedByTheOfficialCatalog() {
        String message = """
            🎣 *Pedido Urutau Jigs*
            ─────────────────────
            """ + ITEMS + """
            ─────────────────────
            *Total: 15 unidades*""";

        var order = CatalogOrderParser.parse(message).orElseThrow();

        assertThat(order.rows()).containsExactly(
            new Row(3, new ProposedItem("Jig Peixinho", 12, "05", 5)),
            new Row(4, new ProposedItem("Urunaja", 10, "01", 5)),
            new Row(5, new ProposedItem("Urunaja", 10, "02", 5)));
        assertThat(order.declaredTotal()).isEqualTo(15);
        assertThat(order.questions()).isEmpty();
        assertThat(order.ambiguous()).isFalse();
    }

    @Test
    void acceptsCopiedItemLinesWithoutTheHeaderOrTotal() {
        var order = CatalogOrderParser.parse(ITEMS.replace("\n", "\\\n").strip()).orElseThrow();

        assertThat(order.rows()).hasSize(3);
        assertThat(order.declaredTotal()).isNull();
        assertThat(order.questions()).isEmpty();
    }

    @Test
    void acceptsTenExplicitBulletItemsWhenAllPhysicalLineBreaksAreLost() {
        String itemLines = """
            • Jig Peixinho | 12g | Cor 01 | Qtd: 5
            • Jig Peixinho | 12g | Cor 04 | Qtd: 5
            • Jig Peixinho | 12g | Cor 06 | Qtd: 5
            • Jig Peixinho | 12g | Cor 05 | Qtd: 5
            • Urunaja | 10g | Cor 01 | Qtd: 5
            • Urunaja | 10g | Cor 02 | Qtd: 5
            • Urunaja | 10g | Cor 06 | Qtd: 5
            • Urunaja | 10g | Cor 08 | Qtd: 5
            • Urunaja | 10g | Cor 07 | Qtd: 5
            • Urunaja | 10g | Cor 05 | Qtd: 5
            """;
        var copies = java.util.List.of(
            itemLines + "---\nTotal: 50 unidades",
            "🎣 *Pedido Urutau Jigs*\n─────────────────────\n" + itemLines
                + "─────────────────────\n*Total: 50 unidades*"
        );
        for (String copy : copies) {
            var order = CatalogOrderParser.parse(copy.replace('\n', ' ')).orElseThrow();

            assertThat(order.rows()).hasSize(10).allMatch(row -> row.item().quantity() == 5);
            assertThat(order.declaredTotal()).isEqualTo(50);
            assertThat(order.questions()).isEmpty();
            assertThat(order.ambiguous()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Qtd: 5", "Qtd.: 5", "Qtde: 5", "Qtde. = 5", "Qte 5", "Quantidade: 5", "Quant. 5",
        "un.: 5", "5 un.", "5 unid.", "5 unidades", "5 und", "5 peças", "5 pçs.", "5x", "5"
    })
    void recognizesCommonQuantityLabelsAndUnits(String count) {
        var order = CatalogOrderParser.parse("Urunaja | 10g | Cor 01 | " + count).orElseThrow();

        assertThat(order.rows()).containsExactly(new Row(1, new ProposedItem("Urunaja", 10, "01", 5)));
        assertThat(order.questions()).isEmpty();
    }

    @Test
    void acceptsForwardedWhatsAppFormattingAndBrazilianThousandsSeparators() {
        var order = CatalogOrderParser.parse("""
            [25/11/2026, 15:00] Pessoa Teste: 🎣 *Pedido Urutau Jigs*
            Encaminhada
            ━━━━━
            > 1. Jig *Peixinho* │ 12 g │ Cor 05 │ 1.200 peças ✅
            ━━━━━
            _Quantidade total: 1.200 unidades_
            """.replace("\n", "\r\n")).orElseThrow();

        assertThat(order.rows()).containsExactly(new Row(4, new ProposedItem("Jig Peixinho", 12, "05", 1200)));
        assertThat(order.declaredTotal()).isEqualTo(1200);
        assertThat(order.questions()).isEmpty();
    }

    @Test
    void preservesOtherRowsAndReportsAnIncompleteLineWithoutAskingForItsKnownFields() {
        var order = CatalogOrderParser.parse("""
            • Jig Peixinho | 12g | Cor 05 | Qtd: 5
            • Urunaja | 10g | Cor 01 | Qtd:
            • Urunaja | 10g | Cor 02 | Qtd: 5
            *Total: 15 unidades*
            """).orElseThrow();

        assertThat(order.rows()).extracting(Row::line).containsExactly(1, 3);
        assertThat(order.declaredTotal()).isEqualTo(15);
        assertThat(order.questions()).containsExactly("Informe a quantidade da linha 2.");
        assertThat(order.ambiguous()).isTrue();
    }

    @Test
    void marksExtraSubstantiveTextForReviewAndDoesNotEchoItInTheQuestion() {
        var order = CatalogOrderParser.parse(ITEMS + "Pode trocar a cor da última isca?").orElseThrow();

        assertThat(order.rows()).hasSize(3);
        assertThat(order.ambiguous()).isTrue();
        assertThat(order.questions()).singleElement().asString().contains("linha 4").doesNotContain("trocar");
    }

    @Test
    void flagsDeclaredTotalMismatchAfterReadingEveryRow() {
        var order = CatalogOrderParser.parse(ITEMS + "*Total: 14 unidades*").orElseThrow();

        assertThat(order.rows()).hasSize(3);
        assertThat(order.declaredTotal()).isEqualTo(14);
        assertThat(order.questions()).singleElement().asString().contains("14", "15");
        assertThat(order.ambiguous()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Qtd: 0", "Qtd: -5", "Qtd: 1,5", "Qtd: 1.5", "Qtd: 100.001", "999999999999999 unidades"})
    void treatsExplicitInvalidNumbersAsDefinitiveErrors(String count) {
        var order = CatalogOrderParser.parse("Urunaja | 10g | Cor 01 | " + count).orElseThrow();

        assertThat(order.rows()).isEmpty();
        assertThat(order.questions()).singleElement().asString().contains("quantidade", "linha 1");
        assertThat(order.ambiguous()).isFalse();
    }

    @Test
    void treatsRepeatedAndMalformedTotalsAsDefinitiveErrors() {
        var repeated = CatalogOrderParser.parse(ITEMS + "*Total: 15 unidades*\n*Total: 8 unidades*").orElseThrow();
        var malformed = CatalogOrderParser.parse(ITEMS + "*Total: quinze unidades*").orElseThrow();

        assertThat(repeated.questions()).singleElement().asString().contains("mais de um total");
        assertThat(malformed.questions()).singleElement().asString().contains("linha 4", "total");
        assertThat(repeated.ambiguous()).isFalse();
        assertThat(malformed.ambiguous()).isFalse();
    }

    @Test
    void leavesFreeFormMessagesAndIncidentalPipesToTheProvider() {
        assertThat(CatalogOrderParser.parse("Quero cinco Urunaja 10g, cor 1.")).isEmpty();
        assertThat(CatalogOrderParser.parse("E também pode mandar outros 5 | Urunaja?")).isEmpty();
    }
}

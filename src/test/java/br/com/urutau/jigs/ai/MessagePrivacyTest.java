package br.com.urutau.jigs.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MessagePrivacyTest {
    private final MessagePrivacy privacy = new MessagePrivacy(List.of(
        new AiInterpreter.Model("Jig Peixinho", List.of(new AiInterpreter.WeightOptions(12, List.of("05"))))
    ));

    @Test
    void preservesExplicitQuantitiesAndSeparatorsWhenSendingAnOrderLineToTheProvider() {
        for (String abbreviation : List.of("Qtd", "Qtd.", "Qtde", "Qtds.", "Qte", "Qte.", "Qt.", "Quant.")) {
            String message = "• Jig Peixinho | 12g | Cor 05 | " + abbreviation + ": 5";
            assertThat(privacy.forProvider(message, false)).as(abbreviation)
                .isEqualTo("Jig Peixinho | 12g | cor 05 | quantidade : 5");
        }
    }

    @Test
    void preservesUnitCountsAndStillRemovesPersonalData() {
        for (String unit : List.of("un.", "und.", "unids", "pç", "pçs", "peças", "unidades")) {
            String message = "[24/11/2026, 13:42] Maria Silva: Declaro 5 " + unit
                + " de Jig Peixinho 12g cor 05. CPF 123.456.789-00, maria@example.com.";
            assertThat(privacy.forProvider(message, false)).as(unit)
                .contains("5 ", "Jig Peixinho 12g cor 05")
                .doesNotContain("Maria", "Silva", "123", "example.com");
        }
    }
}

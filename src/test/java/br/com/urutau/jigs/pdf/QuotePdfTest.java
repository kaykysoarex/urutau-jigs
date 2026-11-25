package br.com.urutau.jigs.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class QuotePdfTest {
    private static final String PREVIEW_LABEL = "PRÉVIA — SEM VALIDADE COMERCIAL";
    private final QuotePdf renderer = new QuotePdf();

    @Test
    void rendersCommercialFieldsAndGroupsDifferentWeightsOnA4() throws IOException {
        var quote = new QuotePdf.Document(24, LocalDate.of(2026, 11, 24),
            new QuotePdf.Client("João Gonçalves", "(62) 99999-1234", "Goiânia", "GO"),
            List.of(line("Urunaja", 10, "01", 2, "17.00"), line("Urunaja", 10, "08", 3, "17.00"),
                line("Urunaja", 12, "03", 5, "19.50")), 10, new BigDecimal("182.50"));

        try (var pdf = Loader.loadPDF(renderer.render(quote))) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            var page = pdf.getPage(0);
            assertThat(page.getMediaBox().getWidth()).isEqualTo(PDRectangle.A4.getWidth());
            assertThat(page.getMediaBox().getHeight()).isEqualTo(PDRectangle.A4.getHeight());
            assertThat(page.getAnnotations()).isEmpty();
            assertThat(textOfPage(pdf, 1))
                .contains("ORÇAMENTO", "Nº 00024", "24/11/2026", "João Gonçalves", "(62) 99999-1234",
                    "Goiânia - GO", "URUNAJA - 10 g", "URUNAJA - 12 g", "Subtotal Urunaja 10 g",
                    "Subtotal Urunaja 12 g", "R$ 85,00", "R$ 97,50", "10 unidades", "R$ 182,50", "Página 1 de 1")
                .doesNotContain(PREVIEW_LABEL, "A definir", "PENDENTE", "Lojista", "Consumidor final", "https://");
        }
    }

    @Test
    void repeatsColumnHeadersAndPreservesEveryRowAcrossPages() throws IOException {
        List<QuotePdf.Line> lines = new ArrayList<>(IntStream.rangeClosed(1, 75)
            .mapToObj(i -> line("Micro-Jig", 8, String.format(Locale.ROOT, "X%02d", i), 2, "16.00"))
            .toList());
        lines.add(line("Jig Urutau", 12, "Y01", 1, "16.00"));
        var quote = new QuotePdf.Document(25, LocalDate.of(2026, 11, 24),
            new QuotePdf.Client("Maria da Silva", null, null, null),
            lines, 151, new BigDecimal("2416.00"));

        try (var pdf = Loader.loadPDF(renderer.render(quote))) {
            int pageCount = pdf.getNumberOfPages();
            assertThat(pageCount).isGreaterThanOrEqualTo(3);
            for (int page = 1; page <= pageCount; page++) {
                assertThat(textOfPage(pdf, page))
                    .contains("Quantidade", "Valor unitário", "Subtotal", "Página " + page + " de " + pageCount);
                assertThat(pdf.getPage(page - 1).getAnnotations()).isEmpty();
            }
            String fullText = new PDFTextStripper().getText(pdf);
            for (QuotePdf.Line line : lines) {
                assertThat(fullText.split("Cor " + line.color(), -1)).hasSize(2);
            }
            assertThat(fullText.split("Subtotal Micro-Jig 8 g", -1)).hasSize(2);
            assertThat(textOfPage(pdf, 2)).contains("MICRO-JIG - 8 g (continuação)");
            assertThat(textOfPage(pdf, pageCount)).contains("151 unidades", "R$ 2.416,00", "Subtotal Jig Urutau 12 g")
                .doesNotContain("Telefone", "Cidade / UF");
        }
    }

    @Test
    void distinguishesAnUnnumberedPreviewAndOnlyLeavesUnknownAmountsUndefined() throws IOException {
        var quote = new QuotePdf.Preview(LocalDate.of(2026, 11, 24),
            new QuotePdf.Client(null, null, null, null),
            List.of(line("Urunaja", 10, "01", 2, "17.00"),
                new QuotePdf.Line("Urunaja", 10, "08", 3, null, null),
                line("Urunaja", 12, "03", 5, "19.50")), 10, null);

        try (var pdf = Loader.loadPDF(renderer.renderPreview(quote))) {
            assertThat(pdf.getDocumentInformation().getTitle()).isEqualTo(PREVIEW_LABEL);
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            String content = textOfPage(pdf, 1);
            assertThat(content)
                .contains("ORÇAMENTO", PREVIEW_LABEL, "Prévia em 24/11/2026", "Não informado",
                    "R$ 17,00", "R$ 34,00", "R$ 19,50", "R$ 97,50", "10 unidades", "Página 1 de 1")
                .doesNotContain("Nº", "Emitido em");
            assertThat(content.split("A definir", -1)).hasSize(5);
            assertThat(content).containsPattern("Subtotal Urunaja 10 g\\s+5\\s+A definir")
                .containsPattern("Subtotal Urunaja 12 g\\s+5\\s+R\\$ 97,50")
                .containsPattern("VALOR TOTAL\\s+A definir");
        }
    }

    @Test
    void identifiesACalculatedPreviewOnEveryPageAndDisplaysItsTotal() throws IOException {
        List<QuotePdf.Line> lines = IntStream.rangeClosed(1, 75)
            .mapToObj(i -> line("Micro-Jig", 8, String.format(Locale.ROOT, "X%02d", i), 2, "16.00"))
            .toList();
        var quote = new QuotePdf.Preview(LocalDate.of(2026, 11, 24),
            new QuotePdf.Client("Maria da Silva", null, null, null), lines, 150, new BigDecimal("2400.00"));

        try (var pdf = Loader.loadPDF(renderer.renderPreview(quote))) {
            int pageCount = pdf.getNumberOfPages();
            assertThat(pageCount).isGreaterThanOrEqualTo(3);
            for (int page = 1; page <= pageCount; page++) {
                assertThat(textOfPage(pdf, page)).contains(PREVIEW_LABEL, "Página " + page + " de " + pageCount)
                    .doesNotContain("Nº", "A definir");
            }
            assertThat(textOfPage(pdf, pageCount)).contains("150 unidades", "R$ 2.400,00");
        }
    }

    private static QuotePdf.Line line(String model, int weight, String color, int quantity, String price) {
        BigDecimal unitPrice = new BigDecimal(price);
        return new QuotePdf.Line(model, weight, color, quantity, unitPrice, unitPrice.multiply(BigDecimal.valueOf(quantity)));
    }

    private static String textOfPage(PDDocument pdf, int pageNumber) throws IOException {
        var extractor = new PDFTextStripper();
        extractor.setStartPage(pageNumber);
        extractor.setEndPage(pageNumber);
        return extractor.getText(pdf);
    }
}

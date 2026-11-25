package br.com.urutau.jigs.pdf;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.text.Normalizer;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

@Component
public class QuotePdf {
    private static final String LOGO = "/static/assets/urutau-logo.png";
    private static final String PREVIEW_LABEL = "PRÉVIA — SEM VALIDADE COMERCIAL";
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu", PT_BR);
    private static final Charset WIN_ANSI = Charset.forName("windows-1252");

    public record Client(String name, String phone, String city, String state) {}

    public record Line(String model, int weight, String color, int quantity,
                       BigDecimal unitPrice, BigDecimal subtotal) {}

    public record Document(long number, LocalDate issuedOn, Client client, List<Line> lines,
                           int totalQuantity, BigDecimal total) {
        public Document {
            lines = List.copyOf(lines);
        }
    }

    public record Preview(LocalDate preparedOn, Client client, List<Line> lines, int totalQuantity,
                          BigDecimal total) {
        public Preview {
            lines = List.copyOf(lines);
        }
    }

    public byte[] render(Document quote) {
        String number = String.format(Locale.ROOT, "%05d", quote.number());
        return render(new LayoutDocument(number, quote.issuedOn(), quote.client(), quote.lines(),
            quote.totalQuantity(), quote.total()));
    }

    public byte[] renderPreview(Preview quote) {
        return render(new LayoutDocument(null, quote.preparedOn(), quote.client(), quote.lines(),
            quote.totalQuantity(), quote.total()));
    }

    private byte[] render(LayoutDocument quote) {
        try (PDDocument pdf = new PDDocument();
             InputStream logoFile = QuotePdf.class.getResourceAsStream(LOGO);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (logoFile == null) {
                throw new IOException("Recurso da marca URUTAU JIGS indisponível.");
            }
            PDImageXObject logo = PDImageXObject.createFromByteArray(pdf, logoFile.readAllBytes(), "Urutau Jigs");
            pdf.getDocumentInformation().setTitle(quote.isPreview() ? PREVIEW_LABEL : "Orçamento Nº " + quote.number());
            pdf.getDocumentInformation().setAuthor("URUTAU JIGS");
            pdf.getDocumentCatalog().setLanguage("pt-BR");
            try (Layout layout = new Layout(pdf, logo, quote)) {
                layout.draw();
            }
            pdf.save(output);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException("Não foi possível gerar o PDF do orçamento.", failure);
        }
    }

    private record LayoutDocument(String number, LocalDate date, Client client, List<Line> lines,
                                  int totalQuantity, BigDecimal total) {
        boolean isPreview() {
            return number == null;
        }
    }

    private record Group(String model, int weight) {}

    private static final class Layout implements AutoCloseable {
        private static final float WIDTH = PDRectangle.A4.getWidth();
        private static final float HEIGHT = PDRectangle.A4.getHeight();
        private static final float LEFT = 30;
        private static final float RIGHT = WIDTH - LEFT;
        private static final float BODY_WIDTH = RIGHT - LEFT;
        private static final float FOOTER_TOP = HEIGHT - 40;
        private static final float BODY_BOTTOM = FOOTER_TOP - 20;
        private static final float NEXT_PAGE_TOP = 110;

        private static final int GROUP_TITLE = 25;
        private static final int TABLE_HEADER = 21;
        private static final int ROW_HEIGHT = 19;
        private static final int GROUP_TOTAL = 24;
        private static final int GROUP_GAP = 15;
        private static final int SUMMARY_HEIGHT = 68;
        private static final int GROUP_HEADER = GROUP_TITLE + TABLE_HEADER;

        private static final float QUANTITY_EDGE = LEFT + 199;
        private static final float PRICE_EDGE = LEFT + 295;
        private static final float SUBTOTAL_EDGE = LEFT + 414;
        private static final float QUANTITY_CENTER = (QUANTITY_EDGE + PRICE_EDGE) / 2;
        private static final Color BLACK = Color.BLACK;
        private static final Color INK = new Color(22, 23, 26);
        private static final Color MUTED = new Color(70, 73, 81);
        private static final Color RULE = new Color(216, 218, 223);
        private static final Color LIGHT = new Color(237, 238, 241);
        private static final Color STRIPE = new Color(247, 248, 249);
        private static final Color ORANGE = new Color(255, 124, 30);
        private static final Color RUST = new Color(200, 66, 0);
        private static final Color RED = new Color(217, 48, 46);
        private static final Color WHITE = Color.WHITE;

        private final PDFont normal = new PDType1Font(FontName.HELVETICA);
        private final PDFont bold = new PDType1Font(FontName.HELVETICA_BOLD);
        private final PDFont italic = new PDType1Font(FontName.HELVETICA_BOLD_OBLIQUE);
        private final NumberFormat currency = NumberFormat.getCurrencyInstance(PT_BR);
        private final NumberFormat integer = NumberFormat.getIntegerInstance(PT_BR);
        private final PDDocument pdf;
        private final PDImageXObject logo;
        private final LayoutDocument quote;
        private PDPageContentStream canvas;
        private float top;

        Layout(PDDocument pdf, PDImageXObject logo, LayoutDocument quote) {
            this.pdf = pdf;
            this.logo = logo;
            this.quote = quote;
        }

        void draw() throws IOException {
            newPage(true);
            drawClient();
            Map<Group, List<Line>> items = new LinkedHashMap<>();
            for (Line line : quote.lines()) {
                items.computeIfAbsent(new Group(line.model(), line.weight()), key -> new ArrayList<>()).add(line);
            }
            int groupsLeft = items.size();
            for (Map.Entry<Group, List<Line>> entry : items.entrySet()) {
                drawGroup(entry.getKey(), entry.getValue(), --groupsLeft == 0);
            }
            drawSummary();
            drawAllFooters();
        }

        private void newPage(boolean first) throws IOException {
            close();
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            canvas = new PDPageContentStream(pdf, page);
            float header = first ? 105 : 90;
            box(0, 0, WIDTH, header, BLACK);
            shape(new float[] {WIDTH * .57f, 0, WIDTH, 0, WIDTH, header, WIDTH * .49f, header}, INK);
            shape(new float[] {0, header - 11, 98, header, 0, header}, ORANGE);
            shape(new float[] {WIDTH - 56, header, WIDTH, header - 8, WIDTH, header}, RED);
            box(0, header, WIDTH, 2, ORANGE);
            drawLogo(first ? 224 : 188, header / 2);

            right("ORÇAMENTO", RIGHT, first ? 39 : 34, italic, first ? 27 : 23, WHITE);
            float numberTop = first ? 49 : 43;
            String label = quote.isPreview() ? PREVIEW_LABEL : "Nº " + quote.number();
            float labelSize = quote.isPreview() ? 12 : 14;
            float pillWidth = Math.max(99, width(label, bold, labelSize) + 22);
            box(RIGHT - pillWidth, numberTop, pillWidth, 24, ORANGE);
            center(label, RIGHT - pillWidth / 2, numberTop + 17, bold, labelSize, BLACK);
            String dateLabel = quote.isPreview() ? "Prévia em " : "Emitido em ";
            right(dateLabel + DATE.format(quote.date()), RIGHT, numberTop + 41, normal, 10, WHITE);
            top = first ? header + 20 : NEXT_PAGE_TOP;
        }

        private void drawLogo(float visibleWidth, float center) throws IOException {
            // Mask only the empty margins of the supplied square artwork.
            float fullWidth = visibleWidth / .888f;
            float fullHeight = fullWidth * logo.getHeight() / logo.getWidth();
            float cropHeight = fullHeight * .303f;
            float cropTop = center - cropHeight / 2;
            canvas.saveGraphicsState();
            canvas.addRect(LEFT, HEIGHT - cropTop - cropHeight, visibleWidth, cropHeight);
            canvas.clip();
            canvas.drawImage(logo, LEFT - fullWidth * .037f,
                HEIGHT - cropTop + fullHeight * .314f - fullHeight, fullWidth, fullHeight);
            canvas.restoreGraphicsState();
        }

        private void drawClient() throws IOException {
            box(LEFT, top, 4, 15, ORANGE);
            text("DADOS DO CLIENTE", LEFT + 12, top + 12, italic, 14, INK);
            top += 22;

            Client client = quote.client();
            boolean hasPhone = hasText(client.phone());
            boolean hasLocation = hasText(client.city()) || hasText(client.state());
            float phoneEdge = LEFT + BODY_WIDTH * .66f;
            float nameWidth = (hasPhone ? phoneEdge - LEFT : BODY_WIDTH) - 24;
            String clientName = quote.isPreview() && !hasText(client.name()) ? "Não informado" : clean(client.name());
            List<String> name = wrap(clientName, nameWidth, bold, 12);
            List<String> phone = hasPhone ? wrap(clean(client.phone()), RIGHT - phoneEdge - 24, normal, 11) : List.of();
            int firstRow = 22 + 14 * Math.max(name.size(), phone.size());
            box(LEFT, top, BODY_WIDTH, firstRow, STRIPE);
            drawClientField("Cliente", name, LEFT + 12, top, bold, 12);
            if (hasPhone) {
                box(phoneEdge, top, .75f, firstRow, RULE);
                drawClientField("Telefone", phone, phoneEdge + 12, top, normal, 11);
            }
            top += firstRow;

            if (hasLocation) {
                String location = Stream.of(client.city(), client.state())
                    .filter(Layout::hasText)
                    .map(Layout::clean)
                    .collect(Collectors.joining(" - "));
                List<String> locationLines = wrap(location, BODY_WIDTH - 24, normal, 11);
                int height = 22 + 14 * locationLines.size();
                box(LEFT, top, BODY_WIDTH, height, STRIPE);
                box(LEFT, top, BODY_WIDTH, .75f, RULE);
                drawClientField("Cidade / UF", locationLines, LEFT + 12, top, normal, 11);
                top += height;
            }
            top += 17;
        }

        private void drawClientField(String label, List<String> lines, float x, float start, PDFont font,
                                     float size) throws IOException {
            text(label, x, start + 12, bold, 9, MUTED);
            for (int i = 0; i < lines.size(); i++) {
                text(lines.get(i), x, start + 26 + 14f * i, font, size, INK);
            }
        }

        private void drawGroup(Group group, List<Line> lines, boolean lastGroup) throws IOException {
            int closingHeight = GROUP_TOTAL + (lastGroup ? GROUP_GAP + SUMMARY_HEIGHT : 0);
            int completeHeight = GROUP_HEADER + lines.size() * ROW_HEIGHT + closingHeight;
            if (completeHeight <= BODY_BOTTOM - NEXT_PAGE_TOP && completeHeight > remaining()) {
                newPage(false);
            }
            int offset = 0;
            while (offset < lines.size()) {
                int remainingRows = lines.size() - offset;
                boolean endsHere = GROUP_HEADER + remainingRows * ROW_HEIGHT + closingHeight <= remaining();
                int rowsHere = endsHere ? remainingRows :
                    Math.min(remainingRows - 1, (int) ((remaining() - GROUP_HEADER) / ROW_HEIGHT));
                if (rowsHere <= 0) {
                    newPage(false);
                    continue;
                }
                drawTableHeader(group, offset > 0);
                for (int index = offset; index < offset + rowsHere; index++) {
                    drawLine(lines.get(index), index % 2 != 0);
                }
                offset += rowsHere;
                if (endsHere) {
                    drawSubtotal(group, lines);
                } else {
                    newPage(false);
                }
            }
            top += GROUP_GAP;
        }

        private void drawTableHeader(Group group, boolean continued) throws IOException {
            String title = clean(group.model().toUpperCase(PT_BR)) + " - " + group.weight() + " g";
            if (continued) {
                title += " (continuação)";
            }
            float titleSize = fit(title, italic, 14, BODY_WIDTH - 59);
            float edge = Math.max(LEFT + 219, LEFT + 24 + width(title, italic, titleSize));
            shape(new float[] {LEFT, top, edge + 15, top, edge, top + GROUP_TITLE, LEFT, top + GROUP_TITLE}, INK);
            shape(new float[] {LEFT, top, LEFT + 17, top, LEFT + 2, top + GROUP_TITLE, LEFT, top + GROUP_TITLE}, ORANGE);
            shape(new float[] {edge + 21, top, edge + 25, top, edge + 10, top + GROUP_TITLE, edge + 6, top + GROUP_TITLE}, ORANGE);
            shape(new float[] {edge + 29, top, edge + 32, top, edge + 17, top + GROUP_TITLE, edge + 14, top + GROUP_TITLE}, RED);
            text(title, LEFT + 20, top + 17, italic, titleSize, WHITE);
            top += GROUP_TITLE;
            box(LEFT, top, BODY_WIDTH, TABLE_HEADER, LIGHT);
            text("Cor", LEFT + 12, top + 14, bold, 10, INK);
            center("Quantidade", QUANTITY_CENTER, top + 14, bold, 10, INK);
            right("Valor unitário", SUBTOTAL_EDGE - 12, top + 14, bold, 10, INK);
            right("Subtotal", RIGHT - 12, top + 14, bold, 10, INK);
            top += TABLE_HEADER;
        }

        private void drawLine(Line line, boolean alternate) throws IOException {
            box(LEFT, top, BODY_WIDTH, ROW_HEIGHT, alternate ? STRIPE : WHITE);
            box(LEFT, top + ROW_HEIGHT - .5f, BODY_WIDTH, .5f, RULE);
            tableRules(ROW_HEIGHT);
            String color = "Cor " + clean(line.color());
            text(color, LEFT + 12, top + 13, normal, fit(color, normal, 10.5f, QUANTITY_EDGE - LEFT - 24), INK);
            center(integer.format(line.quantity()), QUANTITY_CENTER, top + 13, normal, 10.5f, INK);
            rightMoney(line.unitPrice(), PRICE_EDGE, SUBTOTAL_EDGE - 12, top + 13, normal, 10.5f, INK);
            rightMoney(line.subtotal(), SUBTOTAL_EDGE, RIGHT - 12, top + 13, normal, 10.5f, INK);
            top += ROW_HEIGHT;
        }

        private void drawSubtotal(Group group, List<Line> lines) throws IOException {
            int quantity = lines.stream().mapToInt(Line::quantity).sum();
            BigDecimal total = lines.stream().anyMatch(line -> line.subtotal() == null)
                ? null : lines.stream().map(Line::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            box(LEFT, top, BODY_WIDTH, GROUP_TOTAL, LIGHT);
            box(SUBTOTAL_EDGE, top, RIGHT - SUBTOTAL_EDGE, GROUP_TOTAL, RUST);
            String label = "Subtotal " + clean(group.model()) + " " + group.weight() + " g";
            text(label, LEFT + 12, top + 16, bold, fit(label, bold, 10.5f, QUANTITY_EDGE - LEFT - 24), INK);
            center(integer.format(quantity), QUANTITY_CENTER, top + 16, bold, 11, INK);
            rightMoney(total, SUBTOTAL_EDGE, RIGHT - 12, top + 16, bold, 12, WHITE);
            top += GROUP_TOTAL;
        }

        private void tableRules(float height) throws IOException {
            box(LEFT, top, .5f, height, RULE);
            box(QUANTITY_EDGE, top, .5f, height, RULE);
            box(PRICE_EDGE, top, .5f, height, RULE);
            box(SUBTOTAL_EDGE, top, .5f, height, RULE);
            box(RIGHT - .5f, top, .5f, height, RULE);
        }

        private void drawSummary() throws IOException {
            if (remaining() < SUMMARY_HEIGHT) {
                newPage(false);
            }
            float totalEdge = LEFT + BODY_WIDTH * .59f;
            box(LEFT, top, BODY_WIDTH, SUMMARY_HEIGHT, INK);
            box(LEFT, top, 4, SUMMARY_HEIGHT, ORANGE);
            box(totalEdge, top, RIGHT - totalEdge, SUMMARY_HEIGHT, ORANGE);
            text("RESUMO DO ORÇAMENTO", LEFT + 18, top + 24, italic, 15, WHITE);
            String quantity = integer.format(quote.totalQuantity()) + (quote.totalQuantity() == 1 ? " unidade" : " unidades");
            text(quantity, LEFT + 18, top + 51, bold, fit(quantity, bold, 20, totalEdge - LEFT - 34), ORANGE);
            right("VALOR TOTAL", RIGHT - 16, top + 24, bold, 11, BLACK);
            rightMoney(quote.total(), totalEdge + 4, RIGHT - 16, top + 53, bold, 26, BLACK);
        }

        private void drawAllFooters() throws IOException {
            close();
            int pageCount = pdf.getNumberOfPages();
            int pageNumber = 0;
            for (PDPage page : pdf.getPages()) {
                pageNumber++;
                try (var footer = new PDPageContentStream(pdf, page, AppendMode.APPEND, true, true)) {
                    canvas = footer;
                    box(0, FOOTER_TOP, WIDTH, HEIGHT - FOOTER_TOP, INK);
                    box(0, FOOTER_TOP, WIDTH, 2, ORANGE);
                    text("URUTAU JIGS", LEFT, FOOTER_TOP + 26, italic, 13, WHITE);
                    box(LEFT + 102, FOOTER_TOP + 14, 1, 14, ORANGE);
                    text("Iscas artificiais", LEFT + 112, FOOTER_TOP + 25, normal, 10, WHITE);
                    right("Página " + pageNumber + " de " + pageCount, RIGHT, FOOTER_TOP + 25, normal, 10, WHITE);
                } finally {
                    canvas = null;
                }
            }
        }

        private float remaining() {
            return BODY_BOTTOM - top;
        }

        private void shape(float[] coordinates, Color color) throws IOException {
            canvas.setNonStrokingColor(color);
            canvas.moveTo(coordinates[0], HEIGHT - coordinates[1]);
            for (int i = 2; i < coordinates.length; i += 2) {
                canvas.lineTo(coordinates[i], HEIGHT - coordinates[i + 1]);
            }
            canvas.closePath();
            canvas.fill();
        }

        private void box(float x, float y, float width, float height, Color color) throws IOException {
            canvas.setNonStrokingColor(color);
            canvas.addRect(x, HEIGHT - y - height, width, height);
            canvas.fill();
        }

        private void text(String message, float x, float baseline, PDFont font, float size, Color color) throws IOException {
            canvas.setNonStrokingColor(color);
            canvas.beginText();
            canvas.setFont(font, size);
            canvas.newLineAtOffset(x, HEIGHT - baseline);
            canvas.showText(message);
            canvas.endText();
        }

        private void right(String message, float edge, float baseline, PDFont font, float size, Color color) throws IOException {
            text(message, edge - width(message, font, size), baseline, font, size, color);
        }

        private void center(String message, float x, float baseline, PDFont font, float size, Color color) throws IOException {
            text(message, x - width(message, font, size) / 2, baseline, font, size, color);
        }

        private void rightMoney(BigDecimal value, float leftEdge, float rightEdge, float baseline, PDFont font,
                                float size, Color color) throws IOException {
            String amount = value == null ? "A definir" : clean(currency.format(value));
            right(amount, rightEdge, baseline, font, fit(amount, font, size, rightEdge - leftEdge - 8), color);
        }

        private static float width(String message, PDFont font, float size) throws IOException {
            return font.getStringWidth(message) / 1000f * size;
        }

        private static float fit(String message, PDFont font, float size, float available) throws IOException {
            float measured = width(message, font, size);
            return measured <= available ? size : size * available / measured;
        }

        private static List<String> wrap(String message, float maxWidth, PDFont font, float size) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : message.split(" ")) {
                if (!current.isEmpty() && width(current + " " + word, font, size) > maxWidth) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                if (!current.isEmpty()) {
                    current.append(' ');
                }
                for (int i = 0; i < word.length(); i++) {
                    char letter = word.charAt(i);
                    if (width(current.toString() + letter, font, size) > maxWidth) {
                        lines.add(current.toString());
                        current.setLength(0);
                    }
                    current.append(letter);
                }
            }
            lines.add(current.toString());
            return lines;
        }

        private static String clean(String value) {
            String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
            normalized = normalized.replaceAll("[\\p{Z}\\s]+", " ").replaceAll("\\p{C}", "").strip();
            return new String(normalized.getBytes(WIN_ANSI), WIN_ANSI);
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }

        @Override
        public void close() throws IOException {
            if (canvas != null) {
                PDPageContentStream openCanvas = canvas;
                canvas = null;
                openCanvas.close();
            }
        }
    }
}

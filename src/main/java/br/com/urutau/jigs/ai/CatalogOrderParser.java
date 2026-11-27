package br.com.urutau.jigs.ai;

import static br.com.urutau.jigs.quotes.QuoteDtos.MAX_ITEMS;
import static br.com.urutau.jigs.quotes.QuoteDtos.MAX_ITEM_QUANTITY;

import br.com.urutau.jigs.ai.AiInterpreter.ProposedItem;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class CatalogOrderParser {
    private static final int MAX_TOTAL_QUANTITY = MAX_ITEMS * MAX_ITEM_QUANTITY;
    private static final String UNIT = "(?:un(?:d?s?|id(?:s|ades?)?)|uds?|pe[çc]as?|p[çc]as?|p[çc]s?|iscas?|ite(?:m|ns)|x)\\.?";
    private static final String QUANTITY = "(?:qtd|qtde|qte|qt|qntd?|quant|quantidades?)\\.?";
    private static final String INTEGER = "[+-]?(?:[0-9]{1,3}(?:\\.[0-9]{3})+|[0-9]+)";
    private static final String COUNT_LABEL = "(?:" + QUANTITY + "|" + UNIT + ")";
    private static final String QUANTITY_PREFIX = "(?:" + COUNT_LABEL + "\\h*[:=]?\\h*)?";
    private static final Pattern AMOUNT = Pattern.compile(
        "(?iu)^" + QUANTITY_PREFIX + "(" + INTEGER + ")\\h*" + COUNT_LABEL + "?[.!]?$");
    private static final Pattern INVALID_NUMERIC_AMOUNT = Pattern.compile(
        "(?iu)^" + QUANTITY_PREFIX + "[+-]?[0-9.,]+\\h*" + COUNT_LABEL + "?[.!]?$");
    private static final Pattern MISSING_AMOUNT = Pattern.compile("(?iu)^" + QUANTITY_PREFIX + "$");
    private static final Pattern ADD = Pattern.compile(
        "(?iu)^\\s*(?:adicione|acrescente|inclua)\\h+(" + INTEGER + ")\\h+(?:" + UNIT + "\\h+(?:de\\h+)?)?"
        + "([^|\\r\\n]{1,120}?)\\h+(?:de\\h+)?([0-9]+\\h*(?:g|gr\\.?|gramas?))"
        + "\\h+(?:(?:na|da|com\\h+a)\\h+)?cor\\h+([a-z0-9][a-z0-9-]{0,15})[.!]?\\s*$");
    private static final Pattern TOTAL = Pattern.compile(
        "(?iu)^(?:total(?:\\h+geral)?|" + QUANTITY + "\\h+total)(?=\\h|[:=]|$)"
            + "(?:\\h+de\\h+" + UNIT + "(?=\\h|[:=]|$))?\\h*[:=]?\\h*(?:de\\h+)?(.*)$");
    private static final Pattern TOTAL_AMOUNT = Pattern.compile("(?iu)^(" + INTEGER + ")\\h*(?:" + UNIT + ")?[.!]?$");
    private static final Pattern GRAMS = Pattern.compile("(?iu)^([+-]?[0-9]+)\\h*(?:g|gr\\.?|gramas?)?$");
    private static final Pattern COLOR = Pattern.compile(
        "(?iu)^(?:cor(?:\\h*[:#-]\\h*|\\h+))?([a-z0-9][a-z0-9-]{0,15})$");
    private static final Pattern COLOR_LABEL = Pattern.compile("(?iu)^cor\\b.*");
    private static final Pattern QUANTITY_LABEL = Pattern.compile("(?iu)^" + QUANTITY + "(?=\\h|[:=]|$).*");
    private static final Pattern HEADER = Pattern.compile("(?iu)^pedido\\h+urutau\\h+jigs\\h*[:!]*$");
    private static final Pattern FORWARDED = Pattern.compile("(?iu)^encaminhad[oa](?:\\h+muitas\\h+vezes)?$");
    private static final Pattern DECORATION = Pattern.compile("^[\\h\\p{So}\\p{M}─━—–_=*~.·-]+$");
    private static final Pattern LEADING_MARKS = Pattern.compile(
        "^(?:[\\h\\p{So}\\p{M}•●◦▪‣*~_`>]+|[+–—-]\\h+|[0-9]{1,3}[.)]\\h+)+");
    private static final Pattern TRAILING_MARKS = Pattern.compile("[\\h\\p{So}\\p{M}*~_`\\\\]+$");
    private static final Pattern FIELD_MARKS = Pattern.compile("^[\\h*~_`\\\\]+|[\\h*~_`\\\\]+$");
    private static final Pattern INLINE_STYLE = Pattern.compile("([*~_`])([^\\r\\n]+?)\\1");
    private static final Pattern SEPARATOR = Pattern.compile("\\h*[|│┃¦;]\\h*|\\h+[–—]\\h+");
    private static final Pattern HIDDEN_FORMAT = Pattern.compile("\\p{Cf}");
    private static final String BULLET_ITEM = "•(?=\\h*[^|•\\r\\n]{1,120}\\|)";
    private static final Pattern INLINE_BULLET = Pattern.compile("(?<=\\S)\\h*" + BULLET_ITEM);
    private static final Pattern INLINE_RULE = Pattern.compile(
        "(?iu)(?<![-─━])\\h*([-─━]{3,})\\h*(?=" + BULLET_ITEM
            + "|[*~_`]*(?:total\\b|" + QUANTITY + "\\h+total\\b))");

    private CatalogOrderParser() {}

    static Optional<Order> parseAddition(String message) {
        var command = ADD.matcher(message);
        if (!command.matches()) return Optional.empty();
        return parse("%s | %s | Cor %s | Qtd: %s".formatted(
            command.group(2), command.group(3), command.group(4), command.group(1)));
    }

    static Optional<Order> parse(String message) {
        String normalized = HIDDEN_FORMAT.matcher(Normalizer.normalize(message, Normalizer.Form.NFKC)).replaceAll("");
        var lines = Arrays.stream(MessagePrivacy.stripChatAuthor(normalized).split("\\R", -1))
            .flatMap(CatalogOrderParser::unfoldLine)
            .map(CatalogOrderParser::cleanLine).toList();
        if (lines.stream().noneMatch(line -> HEADER.matcher(line).matches() || looksLikeItem(line))) {
            return Optional.empty();
        }

        var rows = new ArrayList<Row>();
        var questions = new ArrayList<String>();
        Integer declaredTotal = null;
        boolean totalSeen = false;
        boolean incomplete = false;
        boolean invalid = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (isFraming(line)) {
                continue;
            }

            var total = TOTAL.matcher(line);
            if (total.matches()) {
                if (totalSeen) {
                    questions.add("A mensagem tem mais de um total. Envie um pedido por mensagem.");
                    invalid = true;
                } else {
                    declaredTotal = readAmount(total.group(1), TOTAL_AMOUNT, MAX_TOTAL_QUANTITY);
                    if (declaredTotal == null) {
                        questions.add("O total informado na linha " + (i + 1) + " deve ser uma quantidade inteira positiva.");
                        invalid = true;
                    }
                }
                totalSeen = true;
                continue;
            }

            var parsed = parseRow(line, i + 1);
            if (parsed.item() == null) {
                questions.add(parsed.question());
                incomplete |= parsed.incomplete();
                invalid |= !parsed.incomplete();
            } else {
                rows.add(new Row(i + 1, parsed.item()));
            }
        }

        if (rows.size() > MAX_ITEMS) {
            questions.add("O pedido deve ter no máximo " + MAX_ITEMS + " linhas de itens.");
            invalid = true;
        }
        if (rows.isEmpty() && questions.isEmpty()) {
            questions.add("Inclua pelo menos uma linha de produto no pedido.");
            incomplete = true;
        }

        if (declaredTotal != null && !incomplete && !invalid) {
            long summed = rows.stream().mapToLong(row -> row.item().quantity()).sum();
            if (summed != declaredTotal) {
                questions.add("O pedido informa " + declaredTotal + " unidades, mas a soma dos itens é "
                    + summed + ". Corrija o total ou as linhas do pedido.");
                invalid = true;
            }
        }
        return Optional.of(new Order(List.copyOf(rows), declaredTotal, List.copyOf(questions), incomplete && !invalid));
    }

    private static boolean looksLikeItem(String line) {
        var fields = fields(line);
        return fields.length >= 3 && (
            GRAMS.matcher(fields[1]).matches() || COLOR_LABEL.matcher(fields[2]).matches()
                || fields.length >= 4 && QUANTITY_LABEL.matcher(fields[3]).matches()
        );
    }

    private static ParsedRow parseRow(String line, int number) {
        var fields = fields(line);
        if (fields.length < 3 || fields.length > 4) {
            return new ParsedRow(null, "Não consegui interpretar a linha " + number + " do pedido. Confira o texto dessa linha.", true);
        }
        if (fields[0].isEmpty()) {
            return new ParsedRow(null, "Informe o modelo da linha " + number + ".", true);
        }
        var grams = GRAMS.matcher(fields[1]);
        if (!grams.matches()) {
            return new ParsedRow(null, fields[1].isEmpty() ? "Informe a gramatura da linha " + number + " em gramas."
                : "Confira o campo da gramatura na linha " + number + ".", true);
        }
        int weight = positiveInteger(grams.group(1), Integer.MAX_VALUE);
        if (weight == 0) {
            return new ParsedRow(null, "A gramatura da linha " + number + " deve ser um número inteiro positivo.", false);
        }
        var color = COLOR.matcher(fields[2]);
        if (!color.matches() || fields[2].equalsIgnoreCase("cor")) {
            return new ParsedRow(null, fields[2].isEmpty() ? "Informe o código da cor da linha " + number + "."
                : "Confira o campo da cor na linha " + number + ".", true);
        }

        String count = fields.length == 4 ? fields[3] : "";
        Integer quantity = readAmount(count, AMOUNT, MAX_ITEM_QUANTITY);
        if (quantity == null) {
            if (INVALID_NUMERIC_AMOUNT.matcher(count).matches()) {
                return new ParsedRow(null,
                    "A quantidade da linha " + number + " deve ser um número inteiro de 1 a 100.000.", false);
            }
            return new ParsedRow(null, MISSING_AMOUNT.matcher(count).matches()
                ? "Informe a quantidade da linha " + number + "."
                : "Confira o campo da quantidade na linha " + number + ".", true);
        }
        return new ParsedRow(new ProposedItem(fields[0], weight, color.group(1), quantity), null, false);
    }

    private static Integer readAmount(String text, Pattern format, int limit) {
        var amount = format.matcher(text.strip());
        if (!amount.matches()) {
            return null;
        }
        int value = positiveInteger(amount.group(1).replace(".", ""), limit);
        return value == 0 ? null : value;
    }

    private static int positiveInteger(String value, int limit) {
        try {
            int number = Integer.parseInt(value);
            return number > 0 && number <= limit ? number : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static Stream<String> unfoldLine(String line) {
        var bullets = INLINE_BULLET.matcher(line);
        var rules = INLINE_RULE.matcher(line);
        if (!bullets.find() && !(line.indexOf('•') >= 0 && rules.find() && looksLikeItem(line))) {
            return Stream.of(line);
        }
        String withRuleBreaks = rules.replaceAll("\n$1\n");
        String withItemBreaks = INLINE_BULLET.matcher(withRuleBreaks).replaceAll("\n•");
        return Arrays.stream(withItemBreaks.split("\n", -1));
    }

    private static String cleanLine(String line) {
        String unstyled = INLINE_STYLE.matcher(line).replaceAll("$2");
        String body = LEADING_MARKS.matcher(unstyled).replaceFirst("").replace("\\|", "|");
        return TRAILING_MARKS.matcher(body).replaceFirst("").strip();
    }

    private static String[] fields(String line) {
        return Arrays.stream(SEPARATOR.split(line, -1))
            .map(field -> FIELD_MARKS.matcher(field).replaceAll("").strip()).toArray(String[]::new);
    }

    private static boolean isFraming(String line) {
        return line.isBlank() || HEADER.matcher(line).matches() || FORWARDED.matcher(line).matches()
            || DECORATION.matcher(line).matches();
    }

    record Row(int line, ProposedItem item) {}
    record Order(List<Row> rows, Integer declaredTotal, List<String> questions, boolean ambiguous) {}
    private record ParsedRow(ProposedItem item, String question, boolean incomplete) {}
}

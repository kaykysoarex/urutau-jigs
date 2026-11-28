package br.com.urutau.jigs.ai;

import static br.com.urutau.jigs.ai.AssistantTools.*;
import static br.com.urutau.jigs.ai.AssistantTools.Tool.*;
import static br.com.urutau.jigs.ai.AssistantCatalog.normalize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

final class AssistantLanguage {
    private static final String MONEY = "(\\d{1,10}(?:[.,]\\d{1,2})?|\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?)";
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);
    private AssistantLanguage() {}

    static Tool classify(String text, AdministrativeAssistant.Section section) {
        boolean customer = has(text, "cliente", "cadastro");
        boolean order = has(text, "pedido", "entregue");
        boolean quote = has(text, "orcamento", "pdf");
        boolean product = has(text, "modelo", "isca", "gramatura", "cor", "combinacao");
        boolean changes = has(text, "altere", "alterar", "mude", "mudar", "troque", "ajuste", "aumente", "aumentar",
            "reduza", "reduzir", "defina", "atualize");
        boolean activation = has(text, "desative", "reative", "desativar", "reativar");
        if (has(text, "variavel de ambiente", "chave de api", "senha do administrador", "railway", "execute sql")) return null;
        if (has(text, "minimum", "minimo", "quantidade minima")) return changes ? SETTINGS_UPDATE : SETTINGS;
        if (has(text, "configure o limite", "limite de lojista") && changes) return SETTINGS_UPDATE;
        if (has(text, "preco", "tabela comercial") && (changes || has(text, "aplique", "aplicar", "uniformize"))) return PRICE_UPDATE;
        if (has(text, "confirme", "confirmar") && has(text, "numero", "n ", " 00") && (quote || order)) return ORDER_CONFIRM;
        if (has(text, "confirme") && quote) return ORDER_CONFIRM;
        if (has(text, "marque", "registre", "registrar") && has(text, "entreg")) return ORDER_DELIVER;
        if (has(text, "cancele", "cancelar") && (quote || order)) return ORDER_CANCEL;
        if (changes && order && has(text, "prazo", "observa")) return ORDER_TERMS;
        if (has(text, "vincule", "vincular", "associe") && (quote || order) && !has(text, "rascunho")) return CUSTOMER_LINK;
        if (has(text, "cadastre", "cadastrar", "crie") && customer && !quote && !has(text, "rascunho")) return CUSTOMER_CREATE;
        if (activation && (customer || section == AdministrativeAssistant.Section.CUSTOMERS) && !product) return CUSTOMER_ACTIVATE;
        if (changes && (customer || section == AdministrativeAssistant.Section.CUSTOMERS) && has(text, "telefone", "nome")) return CUSTOMER_EDIT;
        if (has(text, "cadastre", "cadastrar", "crie") && has(text, "novo modelo", "nova isca")) return PRODUCT_CREATE;
        if (has(text, "rascunho") && has(text, "transfira", "transferir", "altere", "remova", "retire", "adicione", "vincule")) return DRAFT_UPDATE;
        if (has(text, "descarte", "descartar") && has(text, "rascunho")) return DRAFT_DISCARD;
        if (has(text, "duplique", "duplicar", "repita", "repetir", "outro orcamento igual", "orcamento igual")) return DRAFT_DUPLICATE;
        if (has(text, "emita", "gerar definitivo") && (quote || has(text, "rascunho"))) return ISSUE;
        if (has(text, "crie", "faca", "prepare", "monte", "gere", "novo") && (quote || has(text, "rascunho"))) return DRAFT_CREATE;
        if (has(text, "adicione", "acrescente", "cadastre") && has(text, "gramatura", "cores", "cor ")) return PRODUCT_UPDATE;
        if (activation || changes && product && has(text, "nome")) return PRODUCT_UPDATE;
        if (has(text, "adicion", "remova", "retire", "altere", "troque") && has(text, "orcamento", "observa", "cor ", "quantidade")) return DRAFT_UPDATE;
        if (has(text, "rascunho", "comec", "nao terminei")) return has(text, "abra", "abrir", "ultimo", "previa") ? DRAFT : DRAFTS;
        if (has(text, "vendemos", "vendas", "mais vendid", "indicador", "compar", "taxa de conversao", "clientes novos",
            "novos clientes", "mais compr", "compraram mais", "valor medio", "quantas iscas", "quanto vend")) return INDICATORS;
        if (has(text, "ultimo", "historico", "comprou") && (order || quote || customer || has(text, "comprou"))) return CUSTOMER_HISTORY;
        if (has(text, "busque", "buscar", "localize", "procure", "mostre", "quais", "liste", "cadastros")
                && customer && !order && !quote) return CUSTOMERS;
        if ((order || quote) && has(text, "abra", "abrir", "visualiz", "numero", "pdf", "detalhes")) return RECORD;
        if (has(text, "preco", "lojista", "consumidor final") && !has(text, "como")) return PRICES;
        if (product && has(text, "modelo", "gramatura", "combina", "cores", "desativados")) return CATALOG;
        if (order && !has(text, "como")) return ORDERS;
        if (quote && has(text, "mostre", "quais", "maior", "lista", "periodo")) return QUOTES;
        if (has(text, "configurac")) return SETTINGS;
        return null;
    }

    static boolean has(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    static String match(String raw, String expression) {
        var found = Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL).matcher(raw);
        return found.find() ? found.group(1).strip() : null;
    }

    static Long recordNumber(String raw) {
        var value = match(raw, "(?:or[çc]amento|pedido|registro)\\s*(?:n(?:[º°o.]|[úu]mero)\\s*[:.]?\\s*)?[# ]*([0-9]{1,18})(?![0-9])");
        if (value == null) value = match(raw, "(?:n[úu]mero|n[º°.]|#)\\s*([0-9]{1,18})(?![0-9])");
        return value == null ? null : Long.parseLong(value);
    }

    static UUID uuid(String raw) {
        var value = match(raw, "\\b([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})\\b");
        return value == null ? null : UUID.fromString(value);
    }

    static String quoted(String raw) { return match(raw, "[\"“]([^\"”]{1,120})[\"”]"); }

    static String customer(String raw) {
        String name = quoted(raw);
        if (name == null) name = match(raw, "(?:cliente\\s+|(?:pedido|or[çc]amento)\\s+d[oa]\\s+|para\\s+(?:[oa]\\s+)?)([^?;,\\n]+)");
        if (name == null) name = match(raw, "(?:o|a)\\s+([^?;,\\n]+?)\\s+j[áa]\\s+comprou");
        return cleanName(name, "\\s+(?:para|com telefone|com este telefone|telefone|porque|mas|e adicione|em produ|neste|no m[eê]s)\\b");
    }

    static String productName(String raw) {
        String found = quoted(raw);
        if (found == null) found = match(raw, "(?:chamad[oa]|nomead[oa])\\s+([^,;\\n]+)");
        if (found == null) found = match(raw, "(?:novo modelo|nova isca)\\s+(?:com\\s+o\\s+nome\\s+de\\s+)?([^,;\\n]+)");
        return cleanName(found, "\\s+(?:com|gramaturas|cores)\\b");
    }

    static String newCustomerName(String raw) {
        String found = quoted(raw);
        if (found == null) found = match(raw, "chamad[oa]\\s+([^,;\\n]+)");
        if (found == null) found = match(raw, "cliente\\s+([^,;\\n]+)");
        return cleanName(found, "\\s+(?:com|telefone|celular|whatsapp)\\b");
    }

    static String cleanName(String value, String suffix) {
        if (value == null) return null;
        var ends = Pattern.compile(suffix, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(value);
        if (ends.find()) value = value.substring(0, ends.start());
        value = value.replaceAll("[?!.:]+$", "").strip();
        if (value.isBlank() || value.length() > 120 || has(normalize(value), "que esta", "ja comprou")) return null;
        return value;
    }

    static String phone(String raw) {
        return match(raw, "(?<![\\p{L}\\d])((?:\\+?55[ ]*)?(?:\\(?[1-9]\\d\\)?[ ]*)?(?:9[ ]*)?\\d{4}[- ]?\\d{4})(?!\\d)");
    }

    static String textAfter(String raw, String prefix) {
        return match(raw, "(?:" + prefix + ")\\s*[:=]?\\s*(\\S[^\\n]*)$");
    }

    static String renamed(String raw) {
        return cleanName(match(raw, "(?:\\bpara|\\bnovo nome)\\s+(.+)$"), "\\s+(?:com|telefone)\\b");
    }

    static Map<Integer, List<String>> exclusions(String raw) {
        String exception = textAfter(raw, "exceto");
        if (exception == null) return Map.of();
        var excluded = new LinkedHashMap<Integer, List<String>>();
        for (var specification : exception.split(";")) {
            var weights = weights(specification);
            var colors = colors(specification);
            if (weights.size() != 1 || colors.isEmpty()) throw invalid("Indique as exclusões com uma gramatura e as respectivas cores.");
            if (excluded.putIfAbsent(weights.getFirst(), colors) != null) throw invalid("Agrupe as exclusões da mesma gramatura.");
        }
        return excluded;
    }

    static List<Integer> weights(String raw) {
        if (Pattern.compile("(?iu)-\\s*\\d+\\s*(?:g\\b|gramas?\\b)").matcher(raw).find()) {
            throw invalid("Informe gramaturas inteiras positivas em gramas.");
        }
        var values = new LinkedHashSet<Integer>();
        var segments = Pattern.compile("(?iu)(?:gramaturas?(?:\\s+d[eo])?\\s*[:=]?\\s*)([0-9gG.,\\s eE]+)").matcher(raw);
        if (segments.find()) {
            var group = segments.group(1);
            if (Pattern.compile("\\d+[.,]\\d+").matcher(group).find()) throw invalid("O catálogo usa gramaturas inteiras em gramas.");
            Pattern.compile("\\d{1,9}").matcher(group).results().map(MatchResult::group).map(Integer::parseInt).forEach(values::add);
        }
        if (values.isEmpty()) {
            var grams = Pattern.compile("(?iu)(?<![\\d.,])([0-9]{1,9})\\s*(?:g\\b|gramas?\\b)").matcher(raw);
            grams.results().map(found -> Integer.valueOf(found.group(1))).forEach(values::add);
        }
        if (Pattern.compile("(?iu)\\d+[.,]\\d+\\s*g\\b").matcher(raw).find()) throw invalid("O catálogo usa gramaturas inteiras em gramas.");
        return List.copyOf(values);
    }

    static List<String> colors(String raw) {
        String segment = match(raw, "\\bcor(?:es)?\\s*[:=]?\\s*([a-zA-Z0-9][^;\\n]*?)(?=\\s+(?:e\\s+(?:pre[çc]o|com)|em|nas?|d[aeo]s?|[àa]|para|pre[çc]o|lojista|consumidor|com)\\b|$|[,;]\\s*(?:pre[çc]o|lojista|consumidor))");
        if (segment == null) return List.of();
        var range = Pattern.compile("(?iu)^\\s*(\\d{1,3})\\s*(?:at[ée]|-)\\s*(\\d{1,3})[.\\s]*$").matcher(segment);
        if (range.matches()) {
            int start = Integer.parseInt(range.group(1)), end = Integer.parseInt(range.group(2));
            if (end < start || end - start >= 100) throw invalid("Informe um intervalo de até 100 cores em ordem crescente.");
            return java.util.stream.IntStream.rangeClosed(start, end).mapToObj(value -> color(Integer.toString(value))).toList();
        }
        var result = new ArrayList<String>();
        for (String value : segment.replaceAll("[.!?]+$", "").split("(?iu)\\s*,\\s*|\\s+e\\s+|\\s*;\\s*")) {
            String token = value.strip();
            if (token.matches("(?iu)[a-z0-9][a-z0-9-]{0,15}") && !has(normalize(token), "atuais", "todas", "disponiveis")) {
                result.add(color(token));
            }
        }
        return result.stream().distinct().toList();
    }

    private static String color(String value) { return value.matches("[0-9]") ? "0" + value : value.toUpperCase(Locale.ROOT); }

    static BigDecimal amount(String raw, String table) {
        String prefix = table == null ? "R\\$\\s*" : "(?:" + table + ")\\s*(?:de\\s+|por\\s+|para\\s+)?[:=]?\\s*(?:R\\$\\s*)?";
        String found = match(raw, prefix + MONEY + "(?![\\d.,]\\d)");
        if (found == null) return null;
        return new BigDecimal(found.contains(",") ? found.replace(".", "").replace(",", ".")
            : found.matches("\\d{1,3}(?:\\.\\d{3})+") ? found.replace(".", "") : found);
    }

    static List<LocalDate> dates(String raw) {
        return Pattern.compile("\\b(?:\\d{2}/\\d{2}/\\d{4}|\\d{4}-\\d{2}-\\d{2})\\b").matcher(raw).results()
            .map(found -> {
                try {
                    return found.group().contains("/") ? LocalDate.parse(found.group(), SHORT_DATE) : LocalDate.parse(found.group());
                } catch (java.time.DateTimeException error) {
                    throw invalid("Informe uma data válida no formato dd/mm/aaaa.");
                }
            }).limit(3).toList();
    }

    static boolean period(String normalized) {
        return has(normalized, "hoje", "ontem", "semana", "este mes", "neste mes", "mes passado", "este ano", "neste ano", "periodo", "intervalo");
    }
}

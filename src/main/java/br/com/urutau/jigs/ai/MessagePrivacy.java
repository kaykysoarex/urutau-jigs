package br.com.urutau.jigs.ai;

import br.com.urutau.jigs.ai.AiInterpreter.Model;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class MessagePrivacy {
    private static final Pattern CHAT_AUTHOR = Pattern.compile(
        "(?m)^\\h*(?:\\[)?\\d{1,2}/\\d{1,2}/\\d{2,4}(?:,)?\\h+\\d{1,2}:\\d{2}(?::\\d{2})?"
            + "(?:\\])?(?:\\h+-)?\\h+[^:\\r\\n]{1,100}:\\h*");
    private static final Pattern LABELED_DATA = Pattern.compile(
        "(?im)^\\h*(?:nome(?:\\h+completo)?|cliente|telefone|contato|whats(?:app)?|celular|e-?mail|cpf|cnpj|rg|"
            + "endere[çc]o|rua|cidade|cep|(?:unidade\\h+federativa|estado|uf)|bairro|complemento|"
            + "destinat[áa]rio)\\h*[:=][^\\r\\n]*");
    private static final Pattern EMAIL = Pattern.compile(
        "(?i)[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9-]+(?:\\.[a-z0-9-]+)+");
    private static final Pattern BRAZILIAN_DOCUMENT = Pattern.compile(
        "(?<![\\p{L}\\d])(?:\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}|"
            + "\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2})(?![\\p{L}\\d])");
    private static final Pattern PHONE = Pattern.compile(
        "(?<![\\p{L}\\d])(?:\\+\\d{1,3}[ .-]?)?(?:\\(?[1-9]\\d\\)?[ .-]?)?"
            + "(?:9[ .-]?)?\\d{4}[ .-]?\\d{4}(?![\\p{L}\\d])");
    private static final Pattern INVISIBLES = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\r\\n\\t]]");
    private static final Pattern LINK_OR_HANDLE = Pattern.compile(
        "(?i)(?:[a-z][a-z0-9+.-]*://|www\\.)\\S+|(?<![\\p{L}\\p{N}])[@#][\\p{L}\\p{N}_.-]+");
    private static final Pattern DATE_OR_TIME = Pattern.compile(
        "(?<!\\d)(?:\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}|\\d{1,2}:\\d{2}(?::\\d{2})?)(?!\\d)");
    private static final Pattern PRIVATE_CLAUSE = Pattern.compile(
        "(?iu)\\b(?:me chamo|(?:meu|seu)\\h+nome(?:\\h+[ée])?|(?:moro|resido|nascid[oa]|nasci)\\h+(?:em|n[ao])"
            + "|(?:meu|seu)\\h+(?:telefone|celular|contato|cpf|c[ée]p|rg|e-?mail|endere[çc]o)|"
            + "(?:cliente|destinat[áa]rio)\\h+(?:[ée]|se chama)|(?:sou|aqui [ée])\\h+"
            + "|(?:n[úu]mero|id|c[óo]digo)\\h+(?:do|da)\\h+(?:pedido|cliente|cadastro)"
            + "|(?:entrega|envia)\\h+(?:para|na|no|em)|rua|avenida|travessa|alameda|rodovia|"
            + "apto|apartamento|endere[çc]o|nascimento|bairro|cep|pix|cpf|cnpj|rg)\\b[^,;.!?\\r\\n]*");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern WORD = Pattern.compile(
        "[\\p{L}\\p{N}]+(?:[-–][\\p{L}\\p{N}]+)*|-\\d+|[|,:;!?+\\-–.\\n]");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]{1,6}(?:[-–][0-9]{1,6})?");
    private static final Pattern WEIGHT = Pattern.compile("[0-9]{1,5}(?:g|gr|gramas?)");
    private static final Pattern LINKER = Pattern.compile("[,:+\\-–]|a|e|de|da|do|ao|ate|ou|para|por|com|entre");
    private static final Set<String> ORDER_WORDS = Set.of(
        "pedido", "item", "itens", "modelo", "modelos", "isca", "iscas", "jig", "jigs", "produto", "produtos",
        "cor", "cores", "codigo", "codigos", "peso", "pesos", "gramatura", "gramaturas", "g", "gr", "grama", "gramas",
        "quantidade", "quantidades", "unidade", "unidades", "peca", "pecas", "par", "pares", "duzia",
        "quero", "queria", "preciso", "gostaria", "adicionar", "adicione", "acrescente", "acrescentar", "mais", "menos",
        "remover", "remova", "tire", "retire", "tirar", "retirar", "excluir", "exclua", "trocar", "troque", "troca",
        "altere", "alterar", "mude", "mudar", "corrigir", "corrija", "corrige", "passe", "passa", "fique", "fica",
        "coloque", "coloca", "colocar", "inclua", "inclui", "incluir", "reduza", "aumente", "substitua", "manter", "mantenha",
        "cada", "tudo", "todos", "toda", "todas", "apenas", "somente", "so", "exceto", "mesma", "mesmo",
        "primeiro", "primeira", "segundo", "segunda", "terceiro", "terceira", "ultimo", "ultima",
        "anterior", "anteriores", "novo", "nova", "restante", "total", "leve", "pesado", "pesada", "maior", "menor",
        "branco", "branca", "preto", "preta", "azul", "verde", "vermelho", "vermelha", "amarelo", "amarela",
        "laranja", "roxo", "roxa", "rosa", "prata", "dourado", "dourada", "natural", "transparente",
        "zero", "um", "uma", "dois", "duas", "tres", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez",
        "onze", "doze", "treze", "catorze", "quatorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove",
        "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa", "cem", "cento", "mil");
    private static final Set<String> CONTEXT_WORDS = Set.of(
        "a", "o", "as", "os", "e", "ou", "de", "do", "da", "dos", "das", "em", "na", "no", "nos", "nas", "por", "para",
        "ao", "aos", "com", "sem", "ate", "entre", "qual", "quais", "quantas", "quantos", "pode", "ser", "seja", "vai",
        "sim", "nao", "isso", "essa", "esse", "essas", "esses", "aquele", "aquela", "certo", "correto", "correta");
    private static final Set<String> SHORT_ANSWERS = Set.of("sim", "nao", "isso", "certo", "correto", "correta");
    private static final Set<String> REPLY_WORDS = Set.of("pode", "ser", "seja", "vai", "na", "no");
    private static final String OMITTED = "[trecho omitido]";
    private static final Set<String> QUANTITY_LABELS = Set.of("qtd", "qtds", "qtde", "qte", "qt", "quant");
    private static final Set<String> UNIT_ABBREVIATIONS = Set.of("un", "und", "unds", "uni", "unid", "unids", "pc", "pcs");

    private final List<ModelName> modelNames;
    private final Map<String, String> colors = new LinkedHashMap<>();

    MessagePrivacy(List<Model> catalog) {
        modelNames = catalog.stream()
            .flatMap(model -> Stream.of(model.model(), model.model().replace('-', ' '))
                .distinct().map(alias -> new ModelName(model.model(), words(alias))))
            .filter(model -> !model.words().isEmpty())
            .sorted(Comparator.comparingInt((ModelName model) -> model.words().size()).reversed())
            .toList();
        for (var model : catalog) {
            for (var weight : model.weights()) {
                weight.colors().forEach(color -> colors.putIfAbsent(normalize(color), color));
            }
        }
    }

    static String stripContactDetails(String message) {
        String clean = Normalizer.normalize(message, Normalizer.Form.NFKC);
        clean = INVISIBLES.matcher(clean).replaceAll("");
        clean = stripChatAuthor(clean);
        clean = LABELED_DATA.matcher(clean).replaceAll("");
        clean = EMAIL.matcher(clean).replaceAll(" ");
        clean = LINK_OR_HANDLE.matcher(clean).replaceAll(" ");
        clean = BRAZILIAN_DOCUMENT.matcher(clean).replaceAll(" ");
        clean = PHONE.matcher(clean).replaceAll(" ");
        clean = DATE_OR_TIME.matcher(clean).replaceAll(" ");
        return PRIVATE_CLAUSE.matcher(clean).replaceAll(" ").strip();
    }

    static String stripChatAuthor(String message) {
        return CHAT_AUTHOR.matcher(message).replaceAll("");
    }

    String forProvider(String message, boolean hasContext) {
        var words = words(stripContactDetails(message));
        var tokens = tokens(words, modelNames, colors);
        var outgoing = new ArrayList<String>();
        boolean hasOrderContent = false;
        boolean shortReply = hasContext && tokens.stream().allMatch(token -> token.kind() == Kind.NUMBER
            || LINKER.matcher(token.text()).matches() || SHORT_ANSWERS.contains(token.text())
            || REPLY_WORDS.contains(token.text()) || ".!?".contains(token.text()));
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.kind() == Kind.ORDER || (hasContext && SHORT_ANSWERS.contains(token.text()))) {
                hasOrderContent = true;
            }
            if (token.kind() == Kind.NUMBER) {
                if (!shortReply && !hasOrderNeighbor(tokens, i, -1) && !hasOrderNeighbor(tokens, i, 1)) {
                    continue;
                }
                hasOrderContent = true;
            }
            if (token.kind() != Kind.OMITTED || outgoing.isEmpty() || !OMITTED.equals(outgoing.getLast())) {
                outgoing.add(token.text());
            }
        }
        return hasOrderContent ? String.join(" ", outgoing).strip() : "";
    }

    private static List<Token> tokens(List<String> words, List<ModelName> models, Map<String, String> colors) {
        var result = new ArrayList<Token>();
        for (int i = 0; i < words.size();) {
            var model = findModel(words, i, models);
            if (model != null) {
                result.add(new Token(model.name(), Kind.ORDER));
                i += model.words().size();
                continue;
            }
            String word = words.get(i++);
            boolean quantityLabel = QUANTITY_LABELS.contains(word);
            boolean unit = UNIT_ABBREVIATIONS.contains(word);
            if (quantityLabel || unit) {
                result.add(new Token(quantityLabel ? "quantidade" : "unidades", Kind.ORDER));
                // An abbreviation that introduces a number should not break its link to that number.
                boolean followsNumber = i > 1 && NUMBER.matcher(words.get(i - 2)).matches();
                boolean explicitLabel = i + 1 < words.size() && ":".equals(words.get(i + 1));
                if (i < words.size() && ".".equals(words.get(i)) && (quantityLabel || !followsNumber || explicitLabel)) {
                    i++;
                }
                continue;
            }
            result.add(token(word, colors));
        }
        return result;
    }

    private static Token token(String word, Map<String, String> colors) {
        if (NUMBER.matcher(word).matches()) {
            return new Token(word, Kind.NUMBER);
        }
        if (colors.containsKey(word)) {
            return new Token(colors.get(word), Kind.ORDER);
        }
        if (ORDER_WORDS.contains(word) || WEIGHT.matcher(word).matches()) {
            return new Token(word, Kind.ORDER);
        }
        if (CONTEXT_WORDS.contains(word) || word.length() == 1 && "|,:;!?+-.–\n".contains(word)) {
            return new Token(word, Kind.CONTEXT);
        }
        return new Token(OMITTED, Kind.OMITTED);
    }

    private static boolean hasOrderNeighbor(List<Token> tokens, int index, int step) {
        for (int i = index + step; i >= 0 && i < tokens.size(); i += step) {
            var token = tokens.get(i);
            if (token.kind() == Kind.ORDER) {
                return true;
            }
            if (token.kind() != Kind.NUMBER && !LINKER.matcher(token.text()).matches()) {
                break;
            }
        }
        return false;
    }

    private static ModelName findModel(List<String> input, int start, List<ModelName> models) {
        ModelName approximate = null;
        boolean ambiguous = false;
        for (var model : models) {
            int length = model.words().size();
            if (start + length > input.size()) {
                continue;
            }
            var phrase = input.subList(start, start + length);
            if (phrase.equals(model.words())) {
                return model;
            }
            if (isOneTypo(phrase, model.words())) {
                if (approximate == null) {
                    approximate = model;
                } else if (approximate.words().size() == length && !approximate.name().equals(model.name())) {
                    ambiguous = true;
                }
            }
        }
        return ambiguous ? null : approximate;
    }

    private static boolean isOneTypo(List<String> input, List<String> expected) {
        boolean different = false;
        for (int i = 0; i < input.size(); i++) {
            String token = input.get(i);
            String known = expected.get(i);
            if (!token.equals(known)) {
                if (different || known.length() < 6 || !matchesSingleEdit(token, known)) {
                    return false;
                }
                different = true;
            }
        }
        return different;
    }

    private static boolean matchesSingleEdit(String input, String expected) {
        if (Math.abs(input.length() - expected.length()) > 1) {
            return false;
        }
        int i = 0;
        while (i < Math.min(input.length(), expected.length()) && input.charAt(i) == expected.charAt(i)) {
            i++;
        }
        if (input.length() < expected.length()) {
            return input.substring(i).equals(expected.substring(i + 1));
        }
        if (input.length() > expected.length()) {
            return input.substring(i + 1).equals(expected.substring(i));
        }
        return input.substring(i + 1).equals(expected.substring(i + 1))
            || i + 1 < input.length() && input.charAt(i) == expected.charAt(i + 1)
                && input.charAt(i + 1) == expected.charAt(i)
                && input.substring(i + 2).equals(expected.substring(i + 2));
    }

    private static List<String> words(String text) {
        var matcher = WORD.matcher(normalize(text));
        var result = new ArrayList<String>();
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private static String normalize(String value) {
        return DIACRITICS.matcher(Normalizer.normalize(value, Normalizer.Form.NFD))
            .replaceAll("").toLowerCase(Locale.ROOT);
    }

    private enum Kind { ORDER, NUMBER, CONTEXT, OMITTED }
    private record Token(String text, Kind kind) {}
    private record ModelName(String name, List<String> words) {}
}

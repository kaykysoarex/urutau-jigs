
package br.com.urutau.jigs.ai;

import br.com.urutau.jigs.catalog.CatalogService;
import br.com.urutau.jigs.catalog.CatalogDtos.ProductView;
import br.com.urutau.jigs.catalog.CatalogDtos.VariantView;
import br.com.urutau.jigs.quotes.CommercialReadModel;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.text.NumberFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdministrativeAssistant {
    enum Section { OVERVIEW, MANUAL, AI, HISTORY, ORDERS, CUSTOMERS, CATALOG, PRICES, SETTINGS }
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern WRITE_INTENT = Pattern.compile("(?iu)\\b(?:confirme|cancele|altere|exclua|apague|emita|envie|registre|mude|marque|atualize|execute)\\b");
    private final CatalogService catalog;
    private final CommercialReadModel commercial;

    AdministrativeAssistant(CatalogService catalog, CommercialReadModel commercial) {
        this.catalog = catalog;
        this.commercial = commercial;
    }

    @Transactional(readOnly = true)
    String answer(Section section, String message) {
        String question = normalize(MessagePrivacy.stripContactDetails(message));
        if (WRITE_INTENT.matcher(question).find()) {
            return "Operações comerciais exigem uma ação do administrador na tela correspondente. Posso explicar o procedimento.";
        }
        if (question.contains("faturamento") || question.contains("estoque") || question.contains("lucro")
                || question.contains("cliente") && (question.contains("pedido") || question.contains("comprou"))) {
            return "Esta consulta ainda não está disponível na assistente. Consulte os pedidos vinculados em Clientes quando precisar do histórico individual.";
        }
        if (question.contains("aguardando") || question.contains("pendentes")) {
            return "Orçamentos aguardando confirmação: " + commercial.counts().awaitingConfirmation() + ". Use Pedidos para abrir os registros.";
        }
        if (question.contains("producao")) {
            return "Pedidos em produção: " + commercial.counts().inProduction() + ". Use o filtro Em produção na página Pedidos.";
        }
        if (question.contains("entregues") || question.contains("foram entregue")) {
            return "Pedidos entregues: " + commercial.counts().delivered() + ". Use o filtro Entregue na página Pedidos.";
        }
        if (question.contains("minimo") || question.contains("quantidade minima")) {
            return "A tabela de lojista começa em " + catalog.wholesaleThreshold() + " unidades por orçamento.";
        }
        if (question.contains("emitir") || question.contains("emissao")) {
            return "Informe o nome do cliente, inclua os itens e confira o resumo. Em seguida, use Emitir orçamento. Na página com IA, revise os itens interpretados antes de emitir.";
        }
        if (question.contains("modelo") || question.contains("gramatura") || question.contains("preco")
                || question.contains("lojista") || question.contains("consumidor")) {
            return catalogAnswer(section, question);
        }
        return switch (section) {
            case MANUAL -> "Neste gerador você pode escolher uma isca, selecionar várias cores e ajustar as quantidades antes de emitir.";
            case AI -> "Cole a mensagem em Interpretação do pedido, revise os itens identificados e confira o resumo.";
            case HISTORY -> "No Histórico de orçamentos você pode localizar documentos emitidos e visualizar ou baixar o PDF original.";
            case ORDERS -> "Posso consultar quantos orçamentos aguardam confirmação e quantos pedidos estão em produção ou entregues.";
            case CUSTOMERS -> "Em Clientes você pode buscar um cadastro e abrir os históricos de orçamentos e pedidos.";
            case CATALOG -> "Posso listar os modelos ativos e consultar as gramaturas de um modelo do catálogo.";
            case PRICES, SETTINGS -> "Posso consultar o mínimo de lojista e os preços vigentes de um modelo.";
            case OVERVIEW -> "Posso orientar a emissão e consultar totais por estado, modelos, gramaturas, preços vigentes e o mínimo de lojista.";
        };
    }

    private String catalogAnswer(Section section, String question) {
        var models = catalog.list().stream()
                .filter(model -> model.active() && model.variants().stream().anyMatch(VariantView::active)).toList();
        String words = " " + question + " ";
        var matched = models.stream().filter(model -> words.contains(" " + normalize(model.name()) + " "))
                .max(Comparator.comparingInt(model -> model.name().length())).orElse(null);
        boolean prices = section == Section.PRICES || question.contains("preco") || question.contains("lojista")
                || question.contains("consumidor");
        if (matched == null) {
            if (prices || question.contains("gramatura")) return "Informe o nome de um modelo ativo para consultar suas opções.";
            return models.isEmpty() ? "O catálogo está sem modelos ativos."
                    : "Modelos ativos: " + String.join(", ", models.stream().map(ProductView::name).toList()) + ".";
        }
        var variants = matched.variants().stream().filter(VariantView::active).toList();
        if (prices) {
            var priceList = variants.stream().map(item -> question.contains("lojista") ? item.wholesalePrice() : item.retailPrice()).toList();
            var defined = priceList.stream().filter(java.util.Objects::nonNull).sorted().toList();
            if (defined.isEmpty()) return matched.name() + " está sem preço cadastrado para a tabela consultada.";
            String table = question.contains("lojista") ? "lojista" : "consumidor final";
            BigDecimal min = defined.getFirst();
            BigDecimal max = defined.getLast();
            var format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"));
            return matched.name() + " na tabela de " + table + ": " + format.format(min)
                    + (min.compareTo(max) == 0 ? "." : " a " + format.format(max) + ".")
                    + (defined.size() == priceList.size() ? "" : " Algumas variantes estão sem preço cadastrado.");
        }
        return matched.name() + " tem as gramaturas " + String.join(", ", variants.stream().map(VariantView::weight)
                .distinct().sorted().map(weight -> weight + " g").toList()) + ".";
    }

    private static String normalize(String text) {
        return MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }
}

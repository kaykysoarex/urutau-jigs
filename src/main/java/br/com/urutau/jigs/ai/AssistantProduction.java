package br.com.urutau.jigs.ai;

import static br.com.urutau.jigs.ai.AssistantLanguage.*;
import static br.com.urutau.jigs.ai.AssistantTools.*;
import static br.com.urutau.jigs.ai.AssistantTools.Tool.*;

import br.com.urutau.jigs.production.ProductionDtos.Deadline;
import br.com.urutau.jigs.production.ProductionDtos.Detail;
import br.com.urutau.jigs.production.ProductionDtos.Entry;
import br.com.urutau.jigs.production.ProductionService;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
class AssistantProduction {
    private static final Action OPEN_QUEUE = new Action("nav", "producao", "Abrir Produção");
    private static final DateTimeFormatter CONFIRMATION = DateTimeFormatter.ofPattern("dd/MM/uuuu 'às' HH:mm")
        .withZone(ZoneId.of("America/Sao_Paulo"));
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private final ProductionService production;

    AssistantProduction(ProductionService production) {
        this.production = production;
    }

    static boolean asksAboutManufacturedUnits(String text) {
        if (!has(text, "quant", "quais", "registre", "registrar", "marque", "marcar", "lance", "controle", "faltam", "falta")) return false;
        if (has(text, "detalhes de fabricacao", "necessidade de fabricacao", "lista de fabricacao", "preciso fabricar", "precisamos fabricar",
                "precisa fabricar", "para fabricar", "devem ser fabricadas")) return false;
        return has(text, "produzid", "fabricad", "quantidade restante", "faltam fabricar", "falta fabricar", "producao concluida",
            "pronto para entrega", "registre a fabricacao", "registrar fabricacao", "registre a producao", "registre producao",
            "marque como pronta", "controle de fabricacao");
    }

    static Tool intent(AdministrativeAssistant.Section section, String text) {
        if (has(text, "confirme", "cancele", "marque", "registre", "altere", "atualize", "vincule", "quanto vendemos", "cliente mais")) {
            return null;
        }
        boolean productionWords = has(text, "producao", "fila", "fabricar", "fabricacao");
        boolean deadlineQuestion = has(text, "pedidos atrasados", "pedidos estao atrasados", "pedidos vencem hoje",
            "pedidos possuem prazo para hoje", "pedidos com prazo para hoje", "pedidos com vencimento hoje");
        if (!productionWords && !deadlineQuestion && section != AdministrativeAssistant.Section.PRODUCTION) return null;
        if (has(text, "compare", "entregues", "cancelados", "aguardando confirmacao", "confirmados neste mes")
                && !has(text, "fila de producao")) return null;
        if (has(text, "abra a fila", "abrir a fila", "abrir producao", "abra producao", "abra a producao", "ir para producao")) {
            return PRODUCTION_LINK;
        }
        if (has(text, "detalhes", "detalhe", "fabricacao", "ver itens", "quais itens", "abra o pedido", "abrir pedido",
                "pedido de producao do cliente", "pedido de producao da cliente")) {
            if (!has(text, "fila de fabricacao", "necessidade de fabricacao") || has(text, "primeir", "cliente", "pedido numero")) {
                return PRODUCTION_DETAILS;
            }
        }
        if (has(text, "primeir", "proximo pedido", "quem esta na frente")) return PRODUCTION_FIRST;
        if (productionWords || deadlineQuestion || has(text, "atrasad", "prazo", "fila", "quantas iscas")) return PRODUCTION_QUEUE;
        return null;
    }

    Reply answer(Actor actor, Tool tool, String raw, String text, Long number) {
        actor.name();
        if (tool == PRODUCTION_LINK) {
            return new Reply("A página Produção mostra a fila atual em ordem FIFO.",
                List.of(new Card("Fila de produção", List.of(), List.of(OPEN_QUEUE))));
        }
        if (tool == PRODUCTION_DETAILS) {
            if (has(text, "primeir", "proximo pedido", "da vez")) return details(production.firstDetail());
            if (number != null) return details(production.detail(number));
            var search = customerLookup(raw);
            if (search.isBlank()) return new Reply("Informe o número do pedido ou o nome do cliente para localizar a fabricação.");
            var matches = production.queue(search, null, 0);
            if (matches.matches() == 1) return details(production.detail(matches.content().getFirst().orderNumber()));
            return new Reply(matches.matches() == 0 ? "Nenhum pedido ativo corresponde a esse cliente."
                : "Encontrei mais de um pedido. Informe o número ou abra um dos registros abaixo.",
                matches.content().stream().limit(8).map(AssistantProduction::card).toList());
        }
        var filter = deadline(text);
        var queue = production.queue(tool == PRODUCTION_FIRST ? "" : customerLookup(raw), filter, 0);
        var indicators = queue.indicators();
        if (tool == PRODUCTION_FIRST) {
            return indicators.first() == null ? new Reply("A fila de produção está vazia.")
                : new Reply("Este é o primeiro pedido da fila de produção.", List.of(card(indicators.first())));
        }
        var summary = "Fila ativa: " + indicators.orders() + " pedidos e " + indicators.demandedUnits() + " iscas demandadas.";
        if (has(text, "quantas iscas", "quantidade total", "quantas unidades", "quantos pedidos")) {
            return new Reply(summary, List.of(new Card("Indicadores", List.of(
                "Atrasados: " + indicators.overdue(), "Vencem hoje: " + indicators.dueToday(),
                "Prazo próximo: " + indicators.upcoming()), List.of(OPEN_QUEUE))));
        }
        var cards = new ArrayList<Card>();
        queue.content().stream().limit(8).map(AssistantProduction::card).forEach(cards::add);
        cards.add(new Card("Fila completa", List.of(), List.of(OPEN_QUEUE)));
        return new Reply(queue.matches() == 0 ? "Nenhum pedido ativo neste resultado. " + summary
            : summary + " A consulta encontrou " + queue.matches() + " pedidos em ordem FIFO.", List.copyOf(cards));
    }

    private static Reply details(Detail detail) {
        var items = detail.items().stream().limit(35).map(item -> item.model() + " · " + item.combination().weight()
            + " g · cor " + item.combination().color() + " · " + item.units() + " unidades"
            + (item.available() ? "" : " · combinação desativada no catálogo")).toList();
        var text = "Necessidades de fabricação do pedido nº " + detail.order().orderNumber() + ".";
        if (detail.items().size() > items.size()) text += " Abra os detalhes da produção para consultar a lista completa.";
        return new Reply(text, List.of(card(detail.order()), new Card("Lista de fabricação", items)));
    }

    private static Card card(Entry item) {
        return new Card(item.position() + "º na fila · Pedido nº " + item.orderNumber() + " · " + item.clientName(),
            List.of(item.units() + " iscas demandadas", "Confirmado em " + CONFIRMATION.format(item.confirmedAt()),
                label(item.deadline()) + (item.expectedOn() == null ? "" : " · prazo comercial de " + DATE.format(item.expectedOn()))),
            List.of(new Action("production", Long.toString(item.orderNumber()), "Ver detalhes da produção")));
    }

    private static String customerLookup(String raw) {
        var quoted = quoted(raw);
        if (quoted != null) return quoted;
        var name = match(raw, "(?:cliente\\s+|(?:pedidos?|produ[çc][ãa]o)\\s+(?:em\\s+produ[çc][ãa]o\\s+)?d[oa]\\s+)([^?;,\\n]+)");
        if (name != null) name = name.replaceFirst("(?iu)^cliente\\s+", "");
        name = cleanName(name, "\\s+(?:em produ[çc][ãa]o|com prazo|na fila|atrasad[oa]s?)\\b");
        var normalized = AssistantCatalog.normalize(name);
        if (normalized.startsWith("que ") || List.of("informado", "informada", "em producao", "primeiro", "da vez").contains(normalized)) return "";
        return name == null ? "" : name;
    }

    private static Deadline deadline(String text) {
        if (has(text, "atrasad", "vencidos")) return Deadline.ATRASADO;
        if (has(text, "hoje")) return Deadline.VENCE_HOJE;
        if (has(text, "prazo proximo", "proximos do prazo")) return Deadline.PROXIMO;
        if (has(text, "sem prazo")) return Deadline.SEM_PRAZO;
        if (has(text, "dentro do prazo")) return Deadline.DENTRO_DO_PRAZO;
        return null;
    }

    private static String label(Deadline deadline) {
        return switch (deadline) {
            case ATRASADO -> "Atrasado";
            case VENCE_HOJE -> "Vence hoje";
            case PROXIMO -> "Prazo próximo";
            case DENTRO_DO_PRAZO -> "Dentro do prazo";
            case SEM_PRAZO -> "Sem prazo definido";
        };
    }
}

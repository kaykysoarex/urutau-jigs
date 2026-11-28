package br.com.urutau.jigs.quotes;

import static br.com.urutau.jigs.ai.AssistantTools.*;
import static br.com.urutau.jigs.ai.AssistantTools.Tool.*;
import static br.com.urutau.jigs.quotes.QuoteDtos.Status.*;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssistantCommercial {
    private final CustomerService customers;
    private final CommercialOrderService orders;
    private final QuoteRepository quotes;

    AssistantCommercial(CustomerService customers, CommercialOrderService orders, QuoteRepository quotes) {
        this.customers = customers;
        this.orders = orders;
        this.quotes = quotes;
    }

    public Reply customers(Actor actor, String search, Boolean active) {
        actor.name();
        var found = customers.list(search, active, 0);
        return new Reply(found.content().isEmpty() ? "Nenhum cadastro encontrado." : "Clientes encontrados. Abra o cadastro para ver mais.",
            found.content().stream().limit(12).map(card -> new Card(card.customer().name(),
                List.of(card.customer().active() ? "Ativo" : "Inativo", "Pedidos confirmados: " + card.sales().orders()),
                List.of(new Action("customer", Long.toString(card.customer().id()), "Abrir cliente")))).toList());
    }

    public Reply customerHistory(Actor actor, String search, Long known, boolean onlyOrders) {
        actor.name();
        var id = customerId(search, known);
        var detail = customers.detail(id);
        var sales = detail.indicators().sales();
        var records = customers.history(id, onlyOrders, 0).content();
        var cards = new ArrayList<Card>();
        var customerActions = new ArrayList<Action>();
        customerActions.add(new Action("customer", Long.toString(id), "Abrir cliente"));
        if (detail.customer().whatsapp() != null) {
            customerActions.add(new Action("whatsapp", detail.customer().whatsapp(), "Abrir WhatsApp"));
        }
        cards.add(new Card(detail.customer().name(), List.of("Orçamentos aguardando confirmação: " + detail.indicators().pending(),
            "Pedidos confirmados: " + sales.orders(), "Iscas adquiridas: " + sales.units(),
            "Valor comercial confirmado: " + currency(sales.approvedTotal())), List.copyOf(customerActions)));
        records.stream().limit(8).map(AssistantCommercial::recordCard).forEach(cards::add);
        Long latest = records.isEmpty() ? null : records.getFirst().quoteNumber();
        return new Reply(onlyOrders ? "Histórico de pedidos, a partir do mais recente." : "Histórico de orçamentos, a partir do mais recente.",
            List.copyOf(cards)).context(id, latest, null);
    }

    @Transactional(readOnly = true)
    public long customerId(String search, Long known) {
        if ((search == null || search.isBlank()) && known != null) return known;
        if (search == null || search.isBlank()) throw invalid("Informe o nome ou o telefone do cliente.");
        var found = customers.list(search, null, 0);
        if (found.content().isEmpty()) throw invalid("Nenhum cliente encontrado com esse nome ou telefone.");
        var exact = found.content().stream().filter(row -> row.customer().name().equalsIgnoreCase(search)).toList();
        if (exact.size() == 1 && found.pages() <= 1) return exact.getFirst().customer().id();
        if (found.content().size() == 1 && found.pages() <= 1) return found.content().getFirst().customer().id();
        throw invalid("Há mais de um cliente possível. Informe um nome completo ou telefone: "
            + String.join(", ", found.content().stream().limit(6).map(row -> row.customer().name()).toList()) + ".");
    }

    public Reply records(Actor actor, boolean onlyOrders, QuoteDtos.Status status, Boolean delivered, String search) {
        actor.name();
        var filter = delivered == null ? null : delivered ? OrderDtos.Fulfillment.ENTREGUE : OrderDtos.Fulfillment.EM_PRODUCAO;
        var listing = orders.list(status, filter, search, true, 0);
        var cards = listing.content().stream().filter(record -> !onlyOrders || record.orderNumber() != null)
            .limit(12).map(AssistantCommercial::recordCard).toList();
        return new Reply(cards.isEmpty() ? "Nenhum registro encontrado nesta consulta." : "Registros encontrados. A lista mostra até 12 resultados.", cards);
    }

    public long quoteId(Actor actor, long number, boolean order) {
        actor.name();
        if (!order) return number;
        var matches = orders.list(null, null, Long.toString(number), true, 0).content();
        return matches.stream().filter(row -> Long.valueOf(number).equals(row.orderNumber())).findFirst()
            .or(() -> matches.stream().filter(row -> row.quoteNumber() == number && row.orderNumber() != null).findFirst())
            .map(OrderDtos.Card::quoteNumber).orElseThrow(() -> invalid("Pedido não encontrado."));
    }

    public Reply record(Actor actor, long number) {
        actor.name();
        var detail = orders.open(number);
        var cards = new ArrayList<Card>();
        cards.add(recordCard(detail.commercial()));
        cards.add(new Card("Itens do documento", detail.items().stream().limit(30)
            .map(line -> line.model() + " " + line.weight() + " g · cor " + line.color() + " · " + line.quantity()
                + " × " + currency(line.unitPrice()) + " = " + currency(line.subtotal())).toList()));
        if (detail.confirmedAt() != null || detail.cancelledAt() != null) {
            var lines = new ArrayList<String>();
            if (detail.confirmedAt() != null) lines.add("Confirmação: " + date(detail.confirmedAt()));
            if (detail.expectedOn() != null) lines.add("Prazo previsto: " + detail.expectedOn());
            if (detail.commercial().deliveredAt() != null) lines.add("Entrega: " + date(detail.commercial().deliveredAt()) + " · " + detail.deliveredBy());
            if (detail.cancellationReason() != null) lines.add("Cancelamento: " + detail.cancellationReason());
            detail.events().stream().limit(8).forEach(event -> lines.add(event.action() + " · " + date(event.occurredAt()) + " · " + event.actor()));
            cards.add(new Card("Histórico do registro", List.copyOf(lines)));
        }
        return new Reply("Documento emitido. Os valores são os registrados na emissão.", List.copyOf(cards))
            .context(detail.customerId(), number, null);
    }

    public Reply customerChange(Actor actor, Tool tool, String search, Long context, String newName, String phone, Boolean active) {
        String admin = actor.name();
        if (tool == CUSTOMER_CREATE) {
            if (newName == null || newName.isBlank() || newName.length() > 120) throw invalid("Informe o nome do novo cliente.");
            if (phone != null && !customers.duplicates(phone, null).isEmpty()) throw invalid("O telefone pertence a um cadastro. Revise a duplicidade em Clientes.");
            return new Proposal(tool, "CLIENTE", "novo", new Card("Cadastrar " + newName,
                List.of("Telefone: " + (phone == null ? "não informado" : phone), "O cadastro será criado ativo.")),
                "", "novo cadastro ativo", id -> {
                    var created = customers.create(input(newName, phone, null), admin);
                    return new Reply("Cliente cadastrado.", List.of(new Card(created.name(), List.of("Ativo"),
                        List.of(new Action("customer", Long.toString(created.id()), "Abrir cliente"))))).context(created.id(), null, null);
                }).preview();
        }
        long id = customerId(search, context);
        var row = customers.detail(id).customer();
        String nextName = newName == null ? row.name() : newName;
        String nextPhone = phone == null ? row.phone() : phone;
        if (nextName.isBlank() || nextName.length() > 120) throw invalid("Informe um nome válido.");
        if (tool == CUSTOMER_EDIT && phone == null && newName == null) throw invalid("Informe o novo nome ou telefone.");
        if (phone != null && !customers.duplicates(phone, id).isEmpty()) throw invalid("O telefone pertence a outro cadastro. Revise a duplicidade em Clientes.");
        var next = tool == CUSTOMER_ACTIVATE ? List.of("Estado atual: " + (row.active() ? "ativo" : "inativo"),
            "Novo estado: " + (Boolean.TRUE.equals(active) ? "ativo" : "inativo"), "O histórico comercial será preservado.")
            : List.of("Nome atual: " + row.name(), "Novo nome: " + nextName,
                "Telefone atual: " + fallback(row.phone()), "Novo telefone: " + fallback(nextPhone));
        return new Proposal(tool, "CLIENTE", Long.toString(id), new Card("Alterar cadastro de " + row.name(), next),
            "ativo=" + row.active(), tool == CUSTOMER_ACTIVATE ? "ativo=" + active : "dados de contato alterados", ignored -> {
                if (tool == CUSTOMER_ACTIVATE) customers.activate(id, new CustomerDtos.Activation(row.version(), active), admin);
                else customers.edit(id, new CustomerDtos.Change(row.version(), input(nextName, nextPhone, row)), admin);
                return new Reply("Cadastro atualizado.").context(id, null, null);
            }).preview();
    }

    public Reply orderChange(Actor actor, Tool tool, long number, String note, LocalDate deadline, Long customer) {
        String admin = actor.name();
        var row = orders.open(number);
        var card = row.commercial();
        String after = switch (tool) {
            case ORDER_CONFIRM -> {
                if (card.status() != PENDENTE) throw invalid("Somente orçamentos pendentes podem ser confirmados.");
                yield "Em produção";
            }
            case ORDER_DELIVER -> {
                if (card.fulfillment() != OrderDtos.Fulfillment.EM_PRODUCAO) throw invalid("Selecione um pedido em produção.");
                yield "Entregue";
            }
            case ORDER_CANCEL -> {
                if (card.status() == CANCELADO || card.deliveredAt() != null) throw invalid("Selecione um registro pendente ou um pedido em produção.");
                if (note == null || note.isBlank() || note.length() > 500) throw invalid("Informe a justificativa do cancelamento.");
                yield "Cancelado";
            }
            case ORDER_TERMS -> {
                if (card.fulfillment() != OrderDtos.Fulfillment.EM_PRODUCAO) throw invalid("Selecione um pedido em produção.");
                if (deadline == null && (note == null || note.isBlank())) throw invalid("Informe o novo prazo ou a observação.");
                if (note != null && note.length() > 1000) throw invalid("A observação aceita até 1.000 caracteres.");
                yield "Prazo ou observações atualizados";
            }
            case CUSTOMER_LINK -> {
                if (customer == null || row.customerId() != null) throw invalid("Selecione um cliente ativo e um registro sem vínculo.");
                yield "Vincular ao cliente " + customers.detail(customer).customer().name();
            }
            default -> throw invalid("Operação comercial inválida.");
        };
        var content = new ArrayList<>(List.of("Cliente: " + row.client().name(), "Estado atual: " + label(card),
            "Resultado: " + after, "Total original: " + currency(card.total())));
        if (deadline != null) content.add("Prazo: " + row.expectedOn() + " → " + deadline);
        if (note != null) {
            if (tool == ORDER_TERMS) content.add("Observação atual: " + fallback(row.notes()));
            content.add("Justificativa ou observação: " + note);
        }
        String auditAfter = tool == CUSTOMER_LINK ? "cliente #" + customer : after + (note == null ? "" : "; texto registrado no histórico comercial");
        return new Proposal(tool, "COMERCIAL", Long.toString(number), new Card("Registro nº " + number, List.copyOf(content)),
            label(card), auditAfter, ignored -> {
                quotes.lockById(number).orElseThrow(() -> invalid("Registro comercial indisponível."));
                unchanged(row, orders.open(number));
                switch (tool) {
                    case ORDER_CONFIRM -> orders.confirm(number, new OrderDtos.Terms(deadline, note, null), admin);
                    case ORDER_DELIVER -> orders.deliver(number, admin);
                    case ORDER_CANCEL -> orders.cancel(number, new OrderDtos.Cancellation(note), admin);
                    case ORDER_TERMS -> orders.edit(number, new OrderDtos.Update(row.version(),
                        new OrderDtos.Terms(deadline == null ? row.expectedOn() : deadline, note == null ? row.notes() : note, null)), admin);
                    case CUSTOMER_LINK -> orders.associate(number, new CustomerDtos.Selection(customer, false, false), admin);
                    default -> throw invalid("Operação comercial inválida.");
                }
                return record(actor, number);
            }).preview();
    }

    Long latestCustomerRecord(long customer, boolean onlyOrders) {
        return customers.history(customer, onlyOrders, 0).content().stream().findFirst()
            .map(OrderDtos.Card::quoteNumber).orElseThrow(() -> invalid("O cliente ainda não possui registros desse tipo."));
    }

    CustomerDtos.Match customer(long id) { return customers.snapshot(id); }

    private static CustomerDtos.Input input(String name, String phone, CustomerDtos.View old) {
        return new CustomerDtos.Input(name, old == null ? null : old.establishment(), phone, old == null ? null : old.email(),
            null, null, old == null ? null : old.notes(), false);
    }

    private static Card recordCard(OrderDtos.Card record) {
        String id = Long.toString(record.quoteNumber());
        return new Card((record.orderNumber() == null ? "Orçamento nº " + id : "Pedido nº " + record.orderNumber()) +
            " · " + record.clientName(), List.of(label(record), record.quantity() + " unidades · " + currency(record.total()),
                record.priceTier() == QuoteDtos.PriceTier.LOJISTA ? "Lojista" : "Consumidor final", date(record.date())),
            List.of(new Action("order", id, "Abrir registro"), new Action("pdf", id, "Visualizar PDF"),
                new Action("download", id, "Baixar PDF"), new Action("share", id, "Compartilhar PDF")));
    }

    static String currency(BigDecimal value) {
        return NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(value);
    }

    private static String label(OrderDtos.Card row) {
        return switch (row.status()) {
            case PENDENTE -> "Aguardando confirmação";
            case CANCELADO -> "Cancelado";
            case CONFIRMADO -> row.deliveredAt() == null ? "Em produção" : "Entregue";
        };
    }

    private static String date(java.time.Instant at) {
        return at.atZone(java.time.ZoneId.of("America/Sao_Paulo")).toLocalDate().toString();
    }
    private static String fallback(String value) { return value == null || value.isBlank() ? "não informado" : value; }
}

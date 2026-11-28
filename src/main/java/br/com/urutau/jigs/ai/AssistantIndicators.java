package br.com.urutau.jigs.ai;

import static br.com.urutau.jigs.ai.AssistantTools.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AssistantIndicators {
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private final JdbcClient jdbc;
    private final Clock clock;

    AssistantIndicators(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    record Period(LocalDate from, LocalDate until) {
        Timestamp start() { return Timestamp.from(from.atStartOfDay(ZONE).toInstant()); }
        Timestamp end() { return Timestamp.from(until.atStartOfDay(ZONE).toInstant()); }
        String label() { return from + " a " + until.minusDays(1); }
    }

    Period period(String text, List<LocalDate> explicit) {
        var now = LocalDate.now(clock.withZone(ZONE));
        if (!explicit.isEmpty()) {
            if (explicit.size() != 2 || explicit.getFirst().isAfter(explicit.getLast())) throw invalid("Informe o intervalo com duas datas válidas.");
            return new Period(explicit.getFirst(), explicit.getLast().plusDays(1));
        }
        var month = now.withDayOfMonth(1);
        if (text.contains("ontem")) return new Period(now.minusDays(1), now);
        if (text.contains("hoje")) return new Period(now, now.plusDays(1));
        if (text.contains("mes passado")) return new Period(month.minusMonths(1), month);
        if (text.contains("esta semana") || text.contains("nesta semana")) {
            var monday = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return new Period(monday, monday.plusWeeks(1));
        }
        if (text.contains("este ano") || text.contains("neste ano")) return new Period(now.withDayOfYear(1), now.withDayOfYear(1).plusYears(1));
        return new Period(month, month.plusMonths(1));
    }

    @Transactional(readOnly = true)
    public Reply report(Actor actor, Period period, boolean compare, boolean rankings) {
        actor.name();
        var cards = new ArrayList<Card>();
        cards.add(summary(period));
        if (compare) {
            var previousMonth = LocalDate.now(clock.withZone(ZONE)).withDayOfMonth(1);
            cards.add(summary(new Period(previousMonth.minusMonths(1), previousMonth)));
        }
        if (rankings) {
            cards.add(ranking(period, "Modelos mais vendidos", "i.model_name", false));
            cards.add(ranking(period, "Gramaturas mais vendidas", "i.weight_grams", false));
            cards.add(ranking(period, "Cores mais vendidas", "i.color_code", false));
            cards.add(ranking(period, "Clientes por quantidade", "c.name", false));
            cards.add(ranking(period, "Clientes por valor", "c.name", true));
        }
        return new Reply("Indicadores comerciais em America/Sao_Paulo. Valores de pedidos confirmados representam compromissos comerciais.",
            List.copyOf(cards));
    }

    private Card summary(Period period) {
        var budgets = query("""
            select count(*) as issued, coalesce(sum(case when q.status = 'PENDENTE' then 1 else 0 end),0) as pending,
                count(o.number) as converted
            from commercial_quote q left join commercial_order o on o.quote_id = q.id
            where q.created_at >= :start and q.created_at < :end
            """, period).query().singleRow();
        var sales = query("""
            select count(*) as confirmed,
                coalesce(sum(case when q.status = 'CONFIRMADO' and o.delivered_at is null then 1 else 0 end),0) as production,
                coalesce(sum(case when q.status = 'CANCELADO' then 1 else 0 end),0) as cancelled,
                coalesce(sum(case when q.status = 'CONFIRMADO' then q.total_quantity else 0 end),0) as units,
                coalesce(sum(case when q.status = 'CONFIRMADO' then q.total else 0 end),0) as amount,
                coalesce(avg(case when q.status = 'CONFIRMADO' then q.total else null end),0) as average_value,
                coalesce(sum(case when q.status = 'CONFIRMADO' and q.price_tier = 'LOJISTA' then 1 else 0 end),0) as wholesale,
                coalesce(sum(case when q.status = 'CONFIRMADO' and q.price_tier = 'CONSUMIDOR_FINAL' then 1 else 0 end),0) as retail
            from commercial_order o join commercial_quote q on q.id = o.quote_id
            where o.confirmed_at >= :start and o.confirmed_at < :end
            """, period).query().singleRow();
        var delivery = query("""
            select count(*) as delivered, coalesce(sum(q.total_quantity),0) as units
            from commercial_order o join commercial_quote q on q.id = o.quote_id
            where o.delivered_at >= :start and o.delivered_at < :end and q.status = 'CONFIRMADO'
            """, period).query().singleRow();
        long newCustomers = query("select count(*) from commercial_customer where created_at >= :start and created_at < :end",
            period).query(Long.class).single();
        long clients = jdbc.sql("select count(*) from commercial_customer").query(Long.class).single();
        int issued = ((Number) budgets.get("issued")).intValue();
        int converted = ((Number) budgets.get("converted")).intValue();
        var conversion = issued == 0 ? "0%" : java.text.NumberFormat.getPercentInstance(java.util.Locale.forLanguageTag("pt-BR"))
            .format((double) converted / issued);
        return new Card(period.label(), List.of("Clientes cadastrados: " + clients + " · novos: " + newCustomers,
            "Orçamentos emitidos: " + issued + " · pendentes: " + budgets.get("pending"),
            "Conversão de orçamentos do período: " + converted + " · " + conversion,
            "Confirmações no período: " + sales.get("confirmed") + " · em produção: " + sales.get("production")
                + " · cancelados após confirmação: " + sales.get("cancelled"),
            "Entregas no período: " + delivery.get("delivered") + " · iscas entregues: " + delivery.get("units"),
            "Iscas em pedidos confirmados vigentes: " + sales.get("units"),
            "Valor comercial confirmado: " + AssistantCatalog.money(decimal(sales.get("amount"))) + " · média: "
                + AssistantCatalog.money(decimal(sales.get("average_value"))),
            "Pedidos de lojistas: " + sales.get("wholesale") + " · consumidores finais: " + sales.get("retail")));
    }

    private Card ranking(Period period, String title, String dimension, boolean amount) {
        // Identifiers are private constants from report(), never input from a message or provider.
        boolean customers = dimension.equals("c.name");
        String sql = "select " + dimension + " as label, sum(" + (customers ? "q.total_quantity" : "i.quantity")
            + ") as units, sum(" + (customers ? "q.total" : "i.subtotal") + ") as amount from commercial_order o "
            + "join commercial_quote q on q.id = o.quote_id "
            + (customers ? "join commercial_customer c on c.id = q.customer_id " : "join commercial_quote_item i on i.quote_id = q.id ")
            + "where o.confirmed_at >= :start and o.confirmed_at < :end and q.status = 'CONFIRMADO' group by "
            + dimension + (customers ? ", c.id" : "") + " order by " + (amount ? "amount" : "units") + " desc, label limit 5";
        var values = query(sql, period).query().listOfRows().stream().map(row -> row.get("label") + " · " + row.get("units")
            + " unidades · " + AssistantCatalog.money(decimal(row.get("amount")))).toList();
        return new Card(title, values.isEmpty() ? List.of("Sem pedidos confirmados neste período.") : values);
    }

    @Transactional(readOnly = true)
    public Reply records(Actor actor, Period period, boolean orders, String status, boolean highest) {
        actor.name();
        String when = orders ? "o.confirmed_at" : "q.created_at";
        var sql = "select q.id, q.client_name, q.status, q.total, q.total_quantity, o.number from commercial_quote q "
            + (orders ? "join" : "left join") + " commercial_order o on o.quote_id = q.id where " + when
            + " >= :start and " + when + " < :end and (:status = '' or q.status = :status)"
            + " order by " + (highest ? "q.total desc" : when + " desc") + ", q.id desc limit " + (highest ? "1" : "12");
        var values = query(sql, period).param("status", status).query().listOfRows();
        return new Reply(values.isEmpty() ? "Nenhum registro em " + period.label() + "." : "Registros de " + period.label() + ".",
            values.stream().map(row -> new Card((orders ? "Pedido nº " + row.get("number") : "Orçamento nº " + row.get("id"))
                + " · " + row.get("client_name"), List.of(row.get("status").toString(), row.get("total_quantity") + " unidades · "
                    + AssistantCatalog.money(decimal(row.get("total")))), List.of(new Action("order", row.get("id").toString(), "Abrir registro")))).toList());
    }

    @Transactional(readOnly = true)
    public Reply production(Actor actor, boolean late) {
        actor.name();
        var sql = """
            select q.id, q.client_name, o.number, o.expected_on from commercial_order o
            join commercial_quote q on q.id = o.quote_id
            where q.status = 'CONFIRMADO' and o.delivered_at is null
            """ + (late ? " and o.expected_on < :today" : "") + " order by o.expected_on, o.number limit 12";
        var statement = jdbc.sql(sql);
        if (late) statement = statement.param("today", LocalDate.now(clock.withZone(ZONE)));
        var values = statement.query().listOfRows();
        return new Reply(values.isEmpty() ? "Nenhum pedido encontrado." : "Até 12 pedidos em produção.",
            values.stream().map(row -> new Card("Pedido nº " + row.get("number") + " · " + row.get("client_name"),
                List.of("Prazo: " + (row.get("expected_on") == null ? "a definir" : row.get("expected_on"))),
                List.of(new Action("order", row.get("id").toString(), "Abrir pedido")))).toList());
    }

    private JdbcClient.StatementSpec query(String sql, Period period) {
        return jdbc.sql(sql).param("start", period.start()).param("end", period.end());
    }
    private static BigDecimal decimal(Object value) { return new BigDecimal(value.toString()); }
}

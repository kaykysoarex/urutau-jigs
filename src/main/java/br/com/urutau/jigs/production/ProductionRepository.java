package br.com.urutau.jigs.production;

import static br.com.urutau.jigs.production.ProductionDtos.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ProductionRepository {
    // Both branches share this eligibility condition. Rank is assigned before any user filter.
    private static final String QUEUE = """
        with production_queue as (
            select row_number() over (order by o.confirmed_at, o.number) as fifo_position,
                o.number as order_number, q.id as quote_number, q.client_name, q.customer_id,
                q.total_quantity, q.total, o.expected_on,
                case
                    when o.expected_on is null then 'SEM_PRAZO'
                    when o.expected_on < :today then 'ATRASADO'
                    when o.expected_on = :today then 'VENCE_HOJE'
                    when o.expected_on <= :near then 'PROXIMO'
                    else 'DENTRO_DO_PRAZO'
                end as deadline
            from commercial_order o
            join commercial_quote q on q.id = o.quote_id
            where q.status = 'CONFIRMADO' and o.delivered_at is null
        )
        """;

    private static final String FILTER = """
        from production_queue p
        where (:deadline = '' or p.deadline = :deadline)
          and (:search = '' or p.order_number = :number
               or locate(lower(:search), lower(p.client_name)) > 0
               or exists (select 1 from commercial_customer c where c.id = p.customer_id
                   and locate(lower(:search), lower(c.name)) > 0)
               or exists (select 1 from commercial_quote_item i where i.quote_id = p.quote_number
                   and locate(lower(:search), lower(i.model_name)) > 0))
        """;

    private final JdbcClient jdbc;

    ProductionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Totals(long orders, long units, long overdue, long upcoming, long dueToday) {}
    record Filter(String search, long number, Deadline deadline) {}
    record Row(long position, long orderNumber, long quoteNumber, String clientName, int units, BigDecimal total,
               LocalDate expectedOn, Deadline deadline) {
        Entry at(Instant confirmedAt) {
            return new Entry(position, orderNumber, quoteNumber, clientName, units, total, confirmedAt,
                expectedOn, deadline, Status.EM_PRODUCAO);
        }
    }

    Totals totals(LocalDate today) {
        return queue("""
            select count(*) as orders, coalesce(sum(total_quantity), 0) as units,
                count(case when deadline = 'ATRASADO' then 1 end) as overdue,
                count(case when deadline = 'PROXIMO' then 1 end) as upcoming,
                count(case when deadline = 'VENCE_HOJE' then 1 end) as due_today
            from production_queue
            """, today).query((rs, row) -> new Totals(rs.getLong("orders"), rs.getLong("units"),
                rs.getLong("overdue"), rs.getLong("upcoming"), rs.getLong("due_today"))).single();
    }

    Optional<Row> first(LocalDate today) {
        return queue("select p.* from production_queue p where p.fifo_position = 1", today)
            .query(ProductionRepository::entry).optional();
    }

    List<Row> page(Filter filter, int page, int size, LocalDate today) {
        return filtered("select p.* ", filter, today, " order by p.fifo_position limit :size offset :offset")
            .param("size", size).param("offset", (long) page * size).query(ProductionRepository::entry).list();
    }

    long count(Filter filter, LocalDate today) {
        return filtered("select count(*) ", filter, today, "").query(Long.class).single();
    }

    Optional<Row> find(long number, LocalDate today) {
        return queue("select p.* from production_queue p where p.order_number = :number", today)
            .param("number", number).query(ProductionRepository::entry).optional();
    }

    Detail detail(Entry order) {
        record Contact(String phone, String notes) {}
        var contact = jdbc.sql("""
            select coalesce(nullif(q.client_phone, ''), c.phone) as phone, o.notes
            from commercial_order o
            join commercial_quote q on q.id = o.quote_id
            left join commercial_customer c on c.id = q.customer_id
            where o.number = :number
            """).param("number", order.orderNumber())
            .query((rs, row) -> new Contact(rs.getString("phone"), rs.getString("notes"))).single();

        // Group by stable variant and historical description. Catalog changes only affect the availability flag.
        var items = jdbc.sql("""
            select i.variant_id, v.product_id, i.model_name, i.weight_grams, i.color_code,
                sum(i.quantity) as units,
                case when v.active = true and p.active = true then true else false end as available
            from commercial_quote_item i
            left join catalog_variant v on v.id = i.variant_id
            left join catalog_product p on p.id = v.product_id
            where i.quote_id = :quote
            group by i.variant_id, v.product_id, i.model_name, i.weight_grams, i.color_code, v.active, p.active
            order by i.model_name, i.weight_grams, i.color_code, i.variant_id
            """).param("quote", order.quoteNumber()).query((rs, row) -> new ManufacturingItem(
                new Combination(rs.getObject("product_id", Long.class), rs.getLong("variant_id"),
                    rs.getInt("weight_grams"), rs.getString("color_code")),
                rs.getString("model_name"), rs.getLong("units"), rs.getBoolean("available"))).list();
        return new Detail(order, contact.phone(), contact.notes(), items);
    }

    private JdbcClient.StatementSpec queue(String sql, LocalDate today) {
        return jdbc.sql(QUEUE + sql).param("today", today).param("near", today.plusDays(2));
    }

    private JdbcClient.StatementSpec filtered(String select, Filter filter, LocalDate today, String suffix) {
        return queue(select + FILTER + suffix, today).param("search", filter.search()).param("number", filter.number())
            .param("deadline", filter.deadline() == null ? "" : filter.deadline().name());
    }

    private static Row entry(ResultSet rs, int row) throws SQLException {
        return new Row(rs.getLong("fifo_position"), rs.getLong("order_number"), rs.getLong("quote_number"),
            rs.getString("client_name"), rs.getInt("total_quantity"), rs.getBigDecimal("total"),
            rs.getObject("expected_on", LocalDate.class), Deadline.valueOf(rs.getString("deadline")));
    }
}

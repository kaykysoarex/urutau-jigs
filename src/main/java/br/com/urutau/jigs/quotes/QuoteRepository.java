
package br.com.urutau.jigs.quotes;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface QuoteRepository extends JpaRepository<CommercialQuote, Long> {
    Optional<CommercialQuote> findByCreatedByAndRequestId(String createdBy, String requestId);
    long countByStatus(QuoteDtos.Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from CommercialQuote q where q.id = :id")
    Optional<CommercialQuote> lockById(@Param("id") long id);

    String COMMERCIAL_FILTER = """
        from CommercialQuote q left join CommercialOrder o on o.quote = q
        where (:status is null or q.status = :status)
          and (:delivered is null or (o.number is not null and ((:delivered = true and o.deliveredAt is not null)
                or (:delivered = false and o.deliveredAt is null))))
          and (:search = '' or locate(lower(:search), lower(q.clientName)) > 0
               or q.id = :number or o.number = :number)
        """;

    String COMMERCIAL_CARD = """
        select new br.com.urutau.jigs.quotes.OrderDtos$Card(
            q.id, o.number, q.clientName, coalesce(q.cancelledAt, o.confirmedAt, q.createdAt),
            q.totalQuantity, q.total, q.status, q.priceTier, o.deliveredAt)
        """;

    @Query(value = COMMERCIAL_CARD + COMMERCIAL_FILTER + """
        order by case when :newest = true then coalesce(q.cancelledAt, o.confirmedAt, q.createdAt) end desc,
                 case when :newest = false then coalesce(q.cancelledAt, o.confirmedAt, q.createdAt) end asc,
                 q.id desc
        """, countQuery = "select count(q) " + COMMERCIAL_FILTER)
    Page<OrderDtos.Card> commercialList(@Param("status") QuoteDtos.Status status, @Param("search") String search,
                                      @Param("number") long number, @Param("newest") boolean newest,
                                      @Param("delivered") Boolean delivered, Pageable page);

    long countByCustomerId(long customerId);
    long countByCustomerIdAndStatus(long customerId, QuoteDtos.Status status);

    interface CustomerSales {
        long getCustomerId();
        long getOrders();
        long getUnits();
        BigDecimal getApprovedTotal();
    }

    @Query("""
        select q.customerId as customerId, count(o) as orders, sum(q.totalQuantity) as units,
               sum(q.total) as approvedTotal
        from CommercialQuote q join CommercialOrder o on o.quote = q
        where q.customerId in :ids and q.status = :status
        group by q.customerId
        """)
    List<CustomerSales> sales(@Param("ids") Collection<Long> ids, @Param("status") QuoteDtos.Status status);

    @Query("""
        select max(o.confirmedAt) from CommercialOrder o where o.quote.customerId = :id
        """)
    Instant lastCustomerOrder(@Param("id") long id);

    String CUSTOMER_FILTER = """
        from CommercialQuote q left join CommercialOrder o on o.quote = q
        where q.customerId = :id and (:orders = false or o.number is not null)
        """;

    @Query(value = COMMERCIAL_CARD + CUSTOMER_FILTER + """
        order by case when :orders = true then o.confirmedAt else q.createdAt end desc, q.id desc
        """, countQuery = "select count(q) " + CUSTOMER_FILTER)
    Page<OrderDtos.Card> customerHistory(@Param("id") long id, @Param("orders") boolean orders, Pageable page);
}


package br.com.urutau.jigs.quotes;

import jakarta.persistence.LockModeType;
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
          and (:search = '' or locate(lower(:search), lower(q.clientName)) > 0
               or q.id = :number or o.number = :number)
        """;

    @Query(value = """
        select new br.com.urutau.jigs.quotes.OrderDtos$Card(
            q.id, o.number, q.clientName, coalesce(q.cancelledAt, o.confirmedAt, q.createdAt),
            q.totalQuantity, q.total, q.status, q.priceTier)
        """ + COMMERCIAL_FILTER + """
        order by case when :newest = true then coalesce(q.cancelledAt, o.confirmedAt, q.createdAt) end desc,
                 case when :newest = false then coalesce(q.cancelledAt, o.confirmedAt, q.createdAt) end asc,
                 q.id desc
        """, countQuery = "select count(q) " + COMMERCIAL_FILTER)
    Page<OrderDtos.Card> commercialList(@Param("status") QuoteDtos.Status status, @Param("search") String search,
                                      @Param("number") long number, @Param("newest") boolean newest, Pageable page);
}

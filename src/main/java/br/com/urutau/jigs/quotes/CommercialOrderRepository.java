
package br.com.urutau.jigs.quotes;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface CommercialOrderRepository extends JpaRepository<CommercialOrder, Long> {
    Optional<CommercialOrder> findByQuoteId(long quoteId);

    @org.springframework.data.jpa.repository.Query("""
        select new br.com.urutau.jigs.quotes.CommercialReadModel$Confirmation(o.number, o.confirmedAt)
        from CommercialOrder o where o.number in :numbers
        """)
    java.util.List<CommercialReadModel.Confirmation> confirmations(
        @org.springframework.data.repository.query.Param("numbers") java.util.Collection<Long> numbers);
    long countByQuoteStatusAndDeliveredAtIsNotNull(QuoteDtos.Status status);
}

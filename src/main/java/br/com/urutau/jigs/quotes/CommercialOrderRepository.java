
package br.com.urutau.jigs.quotes;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface CommercialOrderRepository extends JpaRepository<CommercialOrder, Long> {
    Optional<CommercialOrder> findByQuoteId(long quoteId);
}

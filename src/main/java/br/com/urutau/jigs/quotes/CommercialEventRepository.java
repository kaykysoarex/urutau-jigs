
package br.com.urutau.jigs.quotes;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface CommercialEventRepository extends JpaRepository<CommercialEvent, Long> {
    List<CommercialEvent> findByQuoteIdOrderByIdDesc(long quoteId);
}

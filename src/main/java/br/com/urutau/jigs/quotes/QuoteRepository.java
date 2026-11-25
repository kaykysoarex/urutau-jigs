package br.com.urutau.jigs.quotes;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface QuoteRepository extends JpaRepository<CommercialQuote, Long> {
    Optional<CommercialQuote> findByCreatedByAndRequestId(String createdBy, String requestId);
}

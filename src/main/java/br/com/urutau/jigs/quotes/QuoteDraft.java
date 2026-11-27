
package br.com.urutau.jigs.quotes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Entity
@Table(name = "quote_draft")
class QuoteDraft {
    @Id
    @Column(length = 36)
    String id;
    @Column(nullable = false, length = 120)
    String owner;
    @Version
    Long version;
    @Column(nullable = false)
    Instant createdAt;
    @Column(nullable = false)
    Instant updatedAt;
    @Column(columnDefinition = "TEXT")
    String content;
    @Column(length = 64)
    String estimateToken;
    @Column(precision = 24, scale = 2)
    BigDecimal estimatedTotal;

    protected QuoteDraft() {}

    QuoteDraft(String id, String owner, Instant now) {
        this.id = id;
        this.owner = owner;
        this.createdAt = now;
    }

    void store(String payload, QuoteDtos.Preview estimate, Instant now) {
        content = payload;
        updatedAt = now;
        estimateToken = estimate == null ? null : estimate.reviewToken();
        estimatedTotal = estimate == null ? null : estimate.total();
    }

    void close(Instant now) {
        // Retain the ID after erasing the content so a delayed creation cannot resurrect it.
        store(null, null, now);
    }

    void requireVersion(Long expected) {
        if (content == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "O rascunho foi finalizado ou descartado.");
        }
        if (!version.equals(expected)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este rascunho foi alterado em outro dispositivo.");
        }
    }
}


package br.com.urutau.jigs.quotes;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "commercial_order")
class CommercialOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long number;
    @Column(nullable = false, length = 36)
    String internalId;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false, unique = true)
    CommercialQuote quote;
    @Column(nullable = false)
    Instant confirmedAt;
    @Column(nullable = false, length = 80)
    String confirmedBy;
    LocalDate expectedOn;
    @Column(nullable = false, length = 1000)
    String notes;
    @Version
    long version;

    protected CommercialOrder() {}

    CommercialOrder(CommercialQuote quote, Instant now, String actor, OrderDtos.Terms terms) {
        this.internalId = UUID.randomUUID().toString();
        this.quote = quote;
        this.confirmedAt = now;
        this.confirmedBy = actor;
        changeTerms(terms);
    }

    void changeTerms(OrderDtos.Terms terms) {
        expectedOn = terms.expectedOn();
        notes = terms.notes() == null ? "" : terms.notes().strip();
    }
}

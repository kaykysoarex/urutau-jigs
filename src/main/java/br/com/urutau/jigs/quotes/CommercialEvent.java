
package br.com.urutau.jigs.quotes;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "commercial_event")
class CommercialEvent {
    enum Action { CONFIRMACAO, EDICAO, CANCELAMENTO, VINCULO, ENTREGA }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false)
    private CommercialQuote quote;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Action action;
    @Column(nullable = false, length = 80)
    private String actor;
    @Column(nullable = false)
    private Instant occurredAt;
    private LocalDate expectedOn;
    @Column(length = 1000)
    private String notes;
    @Column(length = 500)
    private String reason;

    protected CommercialEvent() {}

    CommercialEvent(CommercialQuote quote, Action action, String actor, Instant now,
                    CommercialOrder order, String reason) {
        this.quote = quote;
        this.action = action;
        this.actor = actor;
        this.occurredAt = now;
        this.expectedOn = order == null ? null : order.expectedOn;
        this.notes = order == null ? null : order.notes;
        this.reason = reason;
    }

    OrderDtos.Event view() {
        return new OrderDtos.Event(action, actor, occurredAt, expectedOn, notes, reason);
    }
}


package br.com.urutau.jigs.quotes;

import org.springframework.stereotype.Service;

@Service
public class CommercialReadModel {
    private final CommercialOrderService orders;

    CommercialReadModel(CommercialOrderService orders) {
        this.orders = orders;
    }

    public Counts counts() {
        var values = orders.counts();
        return new Counts(values.pending(), values.inProduction(), values.delivered());
    }

    public record Counts(long awaitingConfirmation, long inProduction, long delivered) {}
}

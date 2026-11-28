
package br.com.urutau.jigs.quotes;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class CommercialReadModel {
    private final CommercialOrderService orders;
    private final CommercialOrderRepository orderRepository;

    CommercialReadModel(CommercialOrderService orders, CommercialOrderRepository orderRepository) {
        this.orders = orders;
        this.orderRepository = orderRepository;
    }

    public Map<Long, Instant> confirmationTimes(Collection<Long> orderNumbers) {
        if (orderNumbers.isEmpty()) return Map.of();
        return orderRepository.confirmations(orderNumbers).stream()
            .collect(Collectors.toUnmodifiableMap(Confirmation::orderNumber, Confirmation::confirmedAt));
    }

    public record Confirmation(long orderNumber, Instant confirmedAt) {}

    public Counts counts() {
        var values = orders.counts();
        return new Counts(values.pending(), values.inProduction(), values.delivered());
    }

    public record Counts(long awaitingConfirmation, long inProduction, long delivered) {}
}

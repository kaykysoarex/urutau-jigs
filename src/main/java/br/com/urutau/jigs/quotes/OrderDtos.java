
package br.com.urutau.jigs.quotes;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

final class OrderDtos {
    private OrderDtos() {}

    record Terms(LocalDate expectedOn, @Size(max = 1000) String notes) {}
    record Update(@NotNull @PositiveOrZero Long version, @NotNull @Valid Terms terms) {}
    record Cancellation(@NotBlank @Size(max = 500) String reason) {}
    record Event(CommercialEvent.Action action, String actor, Instant occurredAt,
                 LocalDate expectedOn, String notes, String reason) {}
    record Card(long quoteNumber, Long orderNumber, String clientName, Instant date, int quantity,
                @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal total,
                QuoteDtos.Status status, QuoteDtos.PriceTier priceTier) {}
    record Counts(long pending, long confirmed, long cancelled) {}
    record Listing(List<Card> content, int page, int pages, Counts counts) {}
    record Detail(Card commercial, QuoteDtos.Client client, List<QuoteDtos.Line> items,
                  String internalId, Instant confirmedAt, String confirmedBy, LocalDate expectedOn,
                  String notes, Long version, Instant cancelledAt, String cancelledBy,
                  String cancellationReason, List<Event> events) {}
}

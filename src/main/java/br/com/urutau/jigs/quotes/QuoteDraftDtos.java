
package br.com.urutau.jigs.quotes;

import br.com.urutau.jigs.quotes.QuoteDtos.ItemInput;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class QuoteDraftDtos {
    private QuoteDraftDtos() {}

    // Editor fields preserve unfinished input. Only validated ItemInputs enter a commercial quote.
    private static final String EDITOR_AMOUNT = "[0-9eE+.-]{0,24}";

    record Selection(@Positive long variantId, @NotNull @Pattern(regexp = EDITOR_AMOUNT) String quantity) {}

    record Editor(@Positive Long modelId, @Positive Integer weight,
                  @NotNull @Pattern(regexp = EDITOR_AMOUNT) String defaultQuantity,
                  @Positive Long editingId, @Pattern(regexp = EDITOR_AMOUNT) String editingQuantity,
                  @NotNull @Size(max = QuoteDtos.MAX_ITEMS) List<@NotNull @Valid Selection> colors) {
        boolean meaningful() {
            return modelId != null || !"1".equals(defaultQuantity) || editingId != null || !colors.isEmpty();
        }
    }

    enum Source { MANUAL, IA }

    record Content(@NotNull @Valid QuoteDtos.Client client,
                   @NotNull @Size(max = QuoteDtos.MAX_ITEMS) List<@NotNull @Valid ItemInput> items,
                   @NotNull @Size(max = 1000) String notes,
                   @NotNull @Valid Editor editor,
                   @NotNull @Size(max = QuoteDtos.MAX_ITEMS) List<@NotNull @Valid ItemInput> pendingAi,
                   @Valid CustomerDtos.Ref customer, Source source) {
        Content {
            if (source == null) source = pendingAi != null && !pendingAi.isEmpty() ? Source.IA : Source.MANUAL;
        }

        Content(QuoteDtos.Client client, List<ItemInput> items, String notes, Editor editor, List<ItemInput> pendingAi,
                CustomerDtos.Ref customer) {
            this(client, items, notes, editor, pendingAi, customer, null);
        }
        Content(QuoteDtos.Client client, List<ItemInput> items, String notes, Editor editor, List<ItemInput> pendingAi) {
            this(client, items, notes, editor, pendingAi, null);
        }


        boolean meaningful() {
            return client.name() != null || client.phone() != null || client.city() != null || client.state() != null
                    || !notes.isBlank() || !items.isEmpty() || !pendingAi.isEmpty() || editor.meaningful();
        }
    }

    record Save(@PositiveOrZero Long version, @NotNull @Valid Content content) {}

    record Listed(UUID id, long version, String clientName, int quantity, int combinations,
                  @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal estimatedTotal, Instant updatedAt, Source source) {}

    record Variant(long variantId, String model, int weight, String color, boolean active) {}

    record Opened(Listed summary, Content content, List<Variant> variants,
                  QuoteDtos.Preview estimate, boolean estimateChanged, boolean editorUnavailable, CustomerDtos.Match customer) {}
}

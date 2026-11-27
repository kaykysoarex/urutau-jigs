package br.com.urutau.jigs.ai;

import java.util.List;
import java.util.UUID;

import br.com.urutau.jigs.quotes.QuoteDtos;
import br.com.urutau.jigs.quotes.QuoteDtos.ItemInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

final class AiDtos {
    private AiDtos() {}

    record Configuration(boolean configured) {}

    record MessageRequest(
        @NotBlank(message = "Digite ou cole uma mensagem para interpretar.")
        @Size(max = 12000, message = "Envie uma mensagem com até 12.000 caracteres.") String message,
        @Valid Workspace workspace
    ) {}

    record Workspace(@NotNull @Size(max = QuoteDtos.MAX_ITEMS) List<@NotNull @Valid ItemInput> items,
                     @NotNull @Size(max = 1000) String notes,
                     @Size(max = 3000) String previous,
                     @Size(max = 8) List<@NotNull @Size(max = 260) String> questions) {}

    record EditView(UUID id, List<ReviewItem> items, String notes, List<String> questions, String previous) {}

    record ConversationView(UUID id, List<ReviewItem> items, List<String> questions, boolean needsClarification) {}

    record ReviewItem(long variantId, String model, int weight, String color, int quantity) {}
}

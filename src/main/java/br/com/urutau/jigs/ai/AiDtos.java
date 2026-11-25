package br.com.urutau.jigs.ai;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

final class AiDtos {
    private AiDtos() {}

    record Configuration(boolean configured) {}

    record MessageRequest(
        @NotBlank(message = "Digite ou cole uma mensagem para interpretar.")
        @Size(max = 12000, message = "Envie uma mensagem com até 12.000 caracteres.") String message
    ) {}

    record ConversationView(UUID id, List<ReviewItem> items, List<String> questions, boolean needsClarification) {}

    record ReviewItem(long variantId, String model, int weight, String color, int quantity) {}
}

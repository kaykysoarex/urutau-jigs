package br.com.urutau.jigs.quotes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonFormat;

public final class QuoteDtos {
    public static final int MAX_ITEMS = 250;
    public static final int MAX_ITEM_QUANTITY = 100000;

    private QuoteDtos() {}

    public enum PriceTier { CONSUMIDOR_FINAL, LOJISTA }
    public enum Status { PENDENTE }

    public interface IssuedClient extends Default {}

    public record ItemInput(
            @Positive(message = "Selecione uma variante válida.") long variantId,
            @Min(value = 1, message = "Informe pelo menos uma unidade por item.")
            @Max(value = MAX_ITEM_QUANTITY, message = "Informe até 100.000 unidades por item.") int quantity) {}

    public record Client(
            @NotBlank(groups = IssuedClient.class, message = "Informe o nome do cliente.")
            @Size(max = 120, message = "O nome deve ter até 120 caracteres.") String name,
            @Size(max = 40, message = "O telefone deve ter até 40 caracteres.") String phone,
            @Size(max = 120, message = "A cidade deve ter até 120 caracteres.") String city,
            @Size(max = 2, message = "Informe uma UF com duas letras.") String state) {}

    public record PreviewRequest(
            @NotNull(message = "Informe os itens.") @Size(max = MAX_ITEMS, message = "Adicione até 250 linhas.")
            List<@NotNull @Valid ItemInput> items) {}

    public record PdfPreviewRequest(
            @Valid Client client,
            @NotEmpty(message = "Adicione ao menos um item.") @Size(max = MAX_ITEMS, message = "Adicione até 250 linhas.")
            List<@NotNull @Valid ItemInput> items) {}

    public record IssueRequest(
            @NotNull(message = "Informe os dados do cliente.") @Valid @ConvertGroup(to = IssuedClient.class) Client client,
            @NotEmpty(message = "Adicione ao menos um item.") @Size(max = MAX_ITEMS, message = "Adicione até 250 linhas.")
            List<@NotNull @Valid ItemInput> items,
            @NotNull(message = "Identificador de emissão ausente.") UUID requestId,
            @NotNull(message = "Confira o orçamento antes de emitir.")
            @Pattern(regexp = "[a-f0-9]{64}", message = "Atualize os valores antes de emitir.") String reviewToken) {}

    public record Line(long variantId, String model, int weight, String color, int quantity,
                       @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unitPrice,
                       @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal subtotal) {}

    public record Preview(int quantity, PriceTier priceTier, int wholesaleThreshold, int remainingForWholesale,
                          @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal total, int missingPrices,
                          List<Line> items, String reviewToken) {}

    public record Summary(long number, String clientName, Instant createdAt, int quantity,
                          @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal total,
                          Status status) {}

    public record History(List<Summary> content, int number, int totalPages, long totalElements) {}
}

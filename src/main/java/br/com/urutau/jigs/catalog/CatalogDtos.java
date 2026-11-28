package br.com.urutau.jigs.catalog;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class CatalogDtos {
    private CatalogDtos() {}

    public record ProductUpdate(
        @NotBlank @Size(max = 120) String name,
        @NotNull Boolean active,
        @NotNull @Size(max = 2000) List<@NotNull @Valid VariantUpdate> variants,
        @Valid InitialPrices prices,
        @jakarta.validation.constraints.PositiveOrZero Long expectedVersion
    ) {
        public ProductUpdate(String name, Boolean active, List<VariantUpdate> variants, InitialPrices prices) {
            this(name, active, variants, prices, null);
        }
        public ProductUpdate(String name, Boolean active, List<VariantUpdate> variants) {
            this(name, active, variants, null, null);
        }
    }

    public record InitialPrices(
        @NotNull @Digits(integer = 10, fraction = 2) @DecimalMin("0.00") BigDecimal retailPrice,
        @NotNull @Digits(integer = 10, fraction = 2) @DecimalMin("0.00") BigDecimal wholesalePrice
    ) {}

    public record VariantUpdate(
        @NotNull @Positive Integer weight,
        @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]{0,15}",
            message = "use até 16 letras, números ou hífens") String color,
        @NotNull Boolean active
    ) {}

    public record PricesUpdate(
        @NotNull @Size(max = 2000) List<@NotNull @Valid VariantPriceUpdate> variants
    ) {}

    public record VariantPriceUpdate(
        @NotNull @Positive Long id,
        @Digits(integer = 10, fraction = 2) @DecimalMin("0.00") BigDecimal retailPrice,
        @Digits(integer = 10, fraction = 2) @DecimalMin("0.00") BigDecimal wholesalePrice
    ) {}

    public record Settings(
        @NotNull @Positive Integer wholesaleThreshold
    ) {}

    public record ProductView(long id, String name, boolean active, List<VariantView> variants,
                              java.time.Instant createdAt, java.time.Instant updatedAt, long version) {
        public ProductView(long id, String name, boolean active, List<VariantView> variants) {
            this(id, name, active, variants, null, null, 0);
        }
    }

    public record VariantView(
        long id, int weight, String color, boolean active, BigDecimal retailPrice, BigDecimal wholesalePrice
    ) {}

    public record VariantForQuote(
        long id, long productId, String model, int weight, String color,
        boolean active, BigDecimal retailPrice, BigDecimal wholesalePrice
    ) {}
}

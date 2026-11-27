
package br.com.urutau.jigs.quotes;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

final class CustomerDtos {
    private CustomerDtos() {}

    record Input(@NotBlank @Size(max = 120) String name, @Size(max = 120) String establishment,
                 @Size(max = 40) String phone, @Email @Size(max = 254) String email,
                 @Size(max = 120) String city, @Size(max = 2) String state, @Size(max = 1000) String notes,
                 Boolean allowSharedPhone) {}
    record Change(@NotNull @PositiveOrZero Long version, @NotNull @Valid Input data) {}
    record Activation(@NotNull @PositiveOrZero Long version, @NotNull Boolean active) {}
    record Ref(@NotNull @Positive Long id, @NotNull @PositiveOrZero Long version) {}
    record Selection(@Positive Long id, Boolean register, Boolean allowSharedPhone) {
        boolean wantsRegistration() { return Boolean.TRUE.equals(register); }
    }
    record View(long id, long version, String name, String establishment, String phone, String whatsapp,
                String email, String city, String state, String notes, boolean active,
                Instant createdAt, Instant updatedAt) {}
    record Match(long id, long version, String name, String establishment, String phone,
                 String city, String state, boolean active) {}
    record Sales(long orders, long units,
                 @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal approvedTotal) {
        static Sales empty() { return new Sales(0, 0, BigDecimal.ZERO.setScale(2)); }
    }
    record Indicators(long quotes, long pending, Sales sales, Instant lastOrderAt) {}
    record Brief(long id, String name, String city, String state, boolean active, String whatsapp) {}
    record Card(Brief customer, Sales sales) {}
    record Page(List<Card> content, int page, int pages) {}
    record Detail(View customer, Indicators indicators) {}
    record History(List<OrderDtos.Card> content, int page, int pages) {}
}

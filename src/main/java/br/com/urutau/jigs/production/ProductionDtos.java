package br.com.urutau.jigs.production;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ProductionDtos {
    private ProductionDtos() {}

    public enum Deadline { ATRASADO, VENCE_HOJE, PROXIMO, DENTRO_DO_PRAZO, SEM_PRAZO }
    public enum Status { EM_PRODUCAO }

    public record Entry(long position, long orderNumber, long quoteNumber, String clientName,
                        int units, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal total,
                        Instant confirmedAt, LocalDate expectedOn, Deadline deadline, Status status) {}

    public record Indicators(long orders, long demandedUnits, long overdue, long upcoming, long dueToday, Entry first) {}

    public record Queue(Indicators indicators, List<Entry> content, int page, long pages, long matches, Instant checkedAt) {}

    // Stable identifiers are separate from the labels printed on the confirmed document.
    public record Combination(Long productId, long variantId, int weight, String color) {}

    public record ManufacturingItem(Combination combination, String model, long units, boolean available) {}

    public record Detail(Entry order, String phone, String notes, List<ManufacturingItem> items) {}
}

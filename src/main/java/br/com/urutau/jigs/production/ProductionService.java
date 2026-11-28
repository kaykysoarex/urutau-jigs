package br.com.urutau.jigs.production;

import static br.com.urutau.jigs.production.ProductionDtos.*;

import br.com.urutau.jigs.quotes.CommercialReadModel;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ProductionService {
    private static final ZoneId COMMERCIAL_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final int PAGE_SIZE = 12;
    private final ProductionRepository repository;
    private final CommercialReadModel commercial;
    private final Clock clock;

    ProductionService(ProductionRepository repository, CommercialReadModel commercial, Clock clock) {
        this.repository = repository;
        this.commercial = commercial;
        this.clock = clock;
    }

    public Queue queue(String search, Deadline deadline, int page) {
        var text = search.strip();
        if (text.length() > 120 || page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Informe uma pesquisa e uma página válidas.");
        Instant checkedAt = clock.instant();
        var today = checkedAt.atZone(COMMERCIAL_ZONE).toLocalDate();
        var totals = repository.totals(today);
        var first = totals.orders() == 0 ? null : repository.first(today).orElseThrow();
        var filter = new ProductionRepository.Filter(text, orderNumber(text), deadline);
        long matches = text.isEmpty() && deadline == null ? totals.orders() : repository.count(filter, today);
        var rows = matches == 0 || (long) page * PAGE_SIZE >= matches
            ? List.<ProductionRepository.Row>of() : repository.page(filter, page, PAGE_SIZE, today);
        // Use the same Instant mapping as Pedidos in both JDBC dialects.
        var numbers = new LinkedHashSet<Long>();
        if (first != null) numbers.add(first.orderNumber());
        rows.forEach(row -> numbers.add(row.orderNumber()));
        var confirmationTimes = commercial.confirmationTimes(numbers);
        var indicators = new Indicators(totals.orders(), totals.units(), totals.overdue(), totals.upcoming(),
            totals.dueToday(), first == null ? null : first.at(confirmationTimes.get(first.orderNumber())));
        var content = rows.stream().map(row -> row.at(confirmationTimes.get(row.orderNumber()))).toList();
        return new Queue(indicators, content, page, (matches + PAGE_SIZE - 1) / PAGE_SIZE, matches, checkedAt);
    }

    public Detail detail(long number) {
        if (number < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe um número de pedido válido.");
        var row = repository.find(number, LocalDate.now(clock.withZone(COMMERCIAL_ZONE)))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Este pedido está fora da fila ativa de produção."));
        return detail(row);
    }

    public Detail firstDetail() {
        var row = repository.first(LocalDate.now(clock.withZone(COMMERCIAL_ZONE)))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "A fila de produção está vazia."));
        return detail(row);
    }

    private Detail detail(ProductionRepository.Row row) {
        var confirmation = commercial.confirmationTimes(List.of(row.orderNumber())).get(row.orderNumber());
        return repository.detail(row.at(confirmation));
    }

    private static long orderNumber(String search) {
        var number = search.replaceFirst("(?iu)^(?:pedido\\h*)?(?:n[ºo°]\\h*|#\\h*)?", "");
        if (!number.matches("[0-9]{1,18}")) return 0;
        return Long.parseLong(number);
    }
}

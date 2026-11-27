
package br.com.urutau.jigs.quotes;

import static br.com.urutau.jigs.quotes.QuoteDtos.Status.*;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class CommercialOrderService {
    private final QuoteRepository quotes;
    private final CommercialOrderRepository orders;
    private final CommercialEventRepository events;
    private final Clock clock;

    CommercialOrderService(QuoteRepository quotes, CommercialOrderRepository orders,
                           CommercialEventRepository events, Clock clock) {
        this.quotes = quotes;
        this.orders = orders;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    OrderDtos.Listing list(QuoteDtos.Status status, String query, boolean newest, int page) {
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe uma página válida.");
        String search = query.strip();
        long number = -1;
        if (search.matches("#?[0-9]+")) {
            try {
                number = Long.parseLong(search.replace("#", ""));
            } catch (NumberFormatException ignored) {
                // A numeric customer name can still match the text search.
            }
        }
        var found = quotes.commercialList(status, search, number, newest, PageRequest.of(page, 18));
        var counts = new OrderDtos.Counts(quotes.countByStatus(PENDENTE), quotes.countByStatus(CONFIRMADO),
                quotes.countByStatus(CANCELADO));
        return new OrderDtos.Listing(found.getContent(), page, found.getTotalPages(), counts);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    OrderDtos.Detail open(long quoteNumber) {
        var quote = quotes.findById(quoteNumber).orElseThrow(CommercialOrderService::missing);
        return detail(quote, orders.findByQuoteId(quoteNumber).orElse(null));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    OrderDtos.Detail confirm(long quoteNumber, OrderDtos.Terms terms, String actor) {
        var quote = locked(quoteNumber);
        var existing = orders.findByQuoteId(quoteNumber).orElse(null);
        if (quote.summary().status() == CANCELADO) throw conflict("Este registro já foi cancelado.");
        if (existing != null) return detail(quote, existing);
        if (quote.summary().status() != PENDENTE) throw conflict("Este orçamento não está aguardando confirmação.");
        validateDate(terms);
        var now = now();
        var order = orders.saveAndFlush(new CommercialOrder(quote, now, actor, terms));
        quote.confirm();
        events.save(new CommercialEvent(quote, CommercialEvent.Action.CONFIRMACAO, actor, now, order, null));
        return detail(quote, order);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    OrderDtos.Detail edit(long quoteNumber, OrderDtos.Update update, String actor) {
        var quote = locked(quoteNumber);
        if (quote.summary().status() != CONFIRMADO) throw conflict("Somente pedidos confirmados podem ser editados.");
        var order = orders.findByQuoteId(quoteNumber).orElseThrow(CommercialOrderService::missing);
        if (order.version != update.version()) throw conflict("O pedido foi atualizado. Abra os detalhes novamente.");
        validateDate(update.terms());
        String notes = update.terms().notes() == null ? "" : update.terms().notes().strip();
        if (!java.util.Objects.equals(order.expectedOn, update.terms().expectedOn()) || !order.notes.equals(notes)) {
            order.changeTerms(update.terms());
            events.save(new CommercialEvent(quote, CommercialEvent.Action.EDICAO, actor, now(), order, null));
            orders.flush();
        }
        return detail(quote, order);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    OrderDtos.Detail cancel(long quoteNumber, OrderDtos.Cancellation request, String actor) {
        var quote = locked(quoteNumber);
        var order = orders.findByQuoteId(quoteNumber).orElse(null);
        if (quote.summary().status() == CANCELADO) return detail(quote, order);
        var now = now();
        var reason = request.reason().strip();
        quote.cancel(now, actor, reason);
        events.save(new CommercialEvent(quote, CommercialEvent.Action.CANCELAMENTO, actor, now, order, reason));
        return detail(quote, order);
    }

    private OrderDtos.Detail detail(CommercialQuote quote, CommercialOrder order) {
        var summary = quote.summary();
        var date = quote.cancelledAt != null ? quote.cancelledAt : order == null ? summary.createdAt() : order.confirmedAt;
        var card = new OrderDtos.Card(summary.number(), order == null ? null : order.number, summary.clientName(), date,
                summary.quantity(), summary.total(), summary.status(), summary.priceTier());
        return new OrderDtos.Detail(card, quote.customer(), quote.approvedItems(), order == null ? null : order.internalId,
                order == null ? null : order.confirmedAt, order == null ? null : order.confirmedBy,
                order == null ? null : order.expectedOn, order == null ? null : order.notes,
                order == null ? null : order.version, quote.cancelledAt, quote.cancelledBy, quote.cancellationReason,
                events.findByQuoteIdOrderByIdDesc(summary.number()).stream().map(CommercialEvent::view).toList());
    }

    private static void validateDate(OrderDtos.Terms terms) {
        if (terms.expectedOn() != null && (terms.expectedOn().getYear() < 1000 || terms.expectedOn().getYear() > 9999)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe uma data prevista válida.");
        }
    }

    private CommercialQuote locked(long quoteNumber) {
        return quotes.lockById(quoteNumber).orElseThrow(CommercialOrderService::missing);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Registro comercial não encontrado.");
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}

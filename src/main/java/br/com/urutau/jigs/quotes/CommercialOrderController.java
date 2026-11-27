
package br.com.urutau.jigs.quotes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
class CommercialOrderController {
    private final CommercialOrderService orders;

    CommercialOrderController(CommercialOrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    ResponseEntity<OrderDtos.Listing> list(@RequestParam(required = false) QuoteDtos.Status status,
            @RequestParam(defaultValue = "") @Size(max = 120) String search,
            @RequestParam(defaultValue = "true") boolean newest,
            @RequestParam(defaultValue = "0") int page) {
        return privateResponse(orders.list(status, search, newest, page));
    }

    @GetMapping("/{quoteNumber}")
    ResponseEntity<OrderDtos.Detail> detail(@PathVariable long quoteNumber) {
        return privateResponse(orders.open(quoteNumber));
    }

    @PostMapping("/{quoteNumber}/confirm")
    ResponseEntity<OrderDtos.Detail> confirm(@PathVariable long quoteNumber, @Valid @RequestBody OrderDtos.Terms terms,
                                             Principal admin) {
        return privateResponse(orders.confirm(quoteNumber, terms, admin.getName()));
    }

    @PutMapping("/{quoteNumber}/terms")
    ResponseEntity<OrderDtos.Detail> edit(@PathVariable long quoteNumber, @Valid @RequestBody OrderDtos.Update change,
                                          Principal admin) {
        return privateResponse(orders.edit(quoteNumber, change, admin.getName()));
    }

    @PutMapping("/{quoteNumber}/customer")
    ResponseEntity<OrderDtos.Detail> associate(@PathVariable long quoteNumber,
            @Valid @RequestBody CustomerDtos.Selection selection, Principal admin) {
        return privateResponse(orders.associate(quoteNumber, selection, admin.getName()));
    }

    @PostMapping("/{quoteNumber}/cancel")
    ResponseEntity<OrderDtos.Detail> cancel(@PathVariable long quoteNumber,
                                           @Valid @RequestBody OrderDtos.Cancellation reason, Principal admin) {
        return privateResponse(orders.cancel(quoteNumber, reason, admin.getName()));
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}

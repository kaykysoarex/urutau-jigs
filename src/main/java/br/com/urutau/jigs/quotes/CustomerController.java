
package br.com.urutau.jigs.quotes;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customers")
class CustomerController {
    private final CustomerService customers;

    CustomerController(CustomerService customers) {
        this.customers = customers;
    }


    @PostMapping("/search")
    ResponseEntity<CustomerDtos.Page> list(@Valid @RequestBody Search request) {
        return confidential(customers.list(request.search(), request.active(), request.page() == null ? 0 : request.page()));
    }

    @PostMapping("/matches")
    ResponseEntity<List<CustomerDtos.Match>> matching(@Valid @RequestBody Search request) {
        return confidential(customers.matching(request.search()));
    }

    @PostMapping("/phone-check")
    ResponseEntity<List<CustomerDtos.Match>> duplicates(@Valid @RequestBody PhoneCheck request) {
        return confidential(customers.duplicates(request.phone(), request.except()));
    }

    @GetMapping("/{id}")
    ResponseEntity<CustomerDtos.Detail> detail(@PathVariable long id) {
        return confidential(customers.detail(id));
    }

    @GetMapping("/{id}/history")
    ResponseEntity<CustomerDtos.History> history(@PathVariable long id, @RequestParam(defaultValue = "false") boolean orders,
                                                 @RequestParam(defaultValue = "0") int page) {
        return confidential(customers.history(id, orders, page));
    }

    @PostMapping
    ResponseEntity<CustomerDtos.View> create(@Valid @RequestBody CustomerDtos.Input customer, Principal admin) {
        return confidential(customers.create(customer, admin.getName()));
    }

    @PutMapping("/{id}")
    ResponseEntity<CustomerDtos.View> edit(@PathVariable long id, @Valid @RequestBody CustomerDtos.Change change,
                                            Principal admin) {
        return confidential(customers.edit(id, change, admin.getName()));
    }

    @PutMapping("/{id}/active")
    ResponseEntity<CustomerDtos.View> activate(@PathVariable long id, @Valid @RequestBody CustomerDtos.Activation change,
                                                Principal admin) {
        return confidential(customers.activate(id, change, admin.getName()));
    }

    private static <T> ResponseEntity<T> confidential(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    record Search(@jakarta.validation.constraints.NotNull @Size(max = 120) String search, Boolean active, Integer page) {}

    record PhoneCheck(@jakarta.validation.constraints.NotNull @Size(max = 40) String phone, @Positive Long except) {}
}

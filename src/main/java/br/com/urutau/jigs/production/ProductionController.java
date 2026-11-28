package br.com.urutau.jigs.production;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/production")
class ProductionController {
    private final ProductionService production;

    ProductionController(ProductionService production) {
        this.production = production;
    }

    @GetMapping
    ResponseEntity<ProductionDtos.Queue> queue(@RequestParam(defaultValue = "") @Size(max = 120) String search,
        @RequestParam(required = false) ProductionDtos.Deadline deadline,
        @RequestParam(defaultValue = "0") @PositiveOrZero int page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(production.queue(search, deadline, page));
    }

    @GetMapping("/{number}")
    ResponseEntity<ProductionDtos.Detail> detail(@PathVariable @Positive long number) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(production.detail(number));
    }
}

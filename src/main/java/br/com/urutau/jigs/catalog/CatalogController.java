package br.com.urutau.jigs.catalog;

import java.util.List;

import br.com.urutau.jigs.catalog.CatalogDtos.PricesUpdate;
import br.com.urutau.jigs.catalog.CatalogDtos.ProductUpdate;
import br.com.urutau.jigs.catalog.CatalogDtos.ProductView;
import br.com.urutau.jigs.catalog.CatalogDtos.Settings;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class CatalogController {
    private final CatalogService catalog;

    CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/catalog")
    List<ProductView> list() {
        return catalog.list();
    }

    @PostMapping("/catalog")
    @ResponseStatus(HttpStatus.CREATED)
    ProductView create(@Valid @RequestBody ProductUpdate request) {
        return catalog.create(request);
    }

    @PutMapping("/catalog/{id}")
    ProductView update(@PathVariable long id, @Valid @RequestBody ProductUpdate request) {
        return catalog.update(id, request);
    }

    @PutMapping("/catalog/prices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updatePrices(@Valid @RequestBody PricesUpdate request) {
        catalog.updatePrices(request);
    }

    @GetMapping("/settings")
    Settings getSettings() {
        return new Settings(catalog.wholesaleThreshold());
    }

    @PutMapping("/settings")
    Settings updateSettings(@Valid @RequestBody Settings request) {
        return catalog.updateSettings(request);
    }
}

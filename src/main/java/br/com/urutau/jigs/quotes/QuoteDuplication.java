package br.com.urutau.jigs.quotes;

import java.util.List;
import java.util.UUID;
import br.com.urutau.jigs.catalog.CatalogService;
import br.com.urutau.jigs.quotes.QuoteDraftDtos.Content;
import br.com.urutau.jigs.quotes.QuoteDraftDtos.Editor;
import br.com.urutau.jigs.quotes.QuoteDraftDtos.Save;
import br.com.urutau.jigs.quotes.QuoteDraftDtos.Source;
import br.com.urutau.jigs.quotes.QuoteDtos.ItemInput;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class QuoteDuplication {
    private final CommercialOrderService orders;
    private final CustomerService customers;
    private final QuoteDraftService drafts;
    private final CatalogService catalog;

    QuoteDuplication(CommercialOrderService orders, CustomerService customers, QuoteDraftService drafts, CatalogService catalog) {
        this.orders = orders;
        this.customers = customers;
        this.drafts = drafts;
        this.catalog = catalog;
    }

    record Copied(UUID id, int quantity, int combinations, List<String> unavailable) {}
    record Prepared(Content content, List<String> unavailable) {}

    @Transactional
    public Copied duplicate(long number, UUID id, String owner) {
        return save(prepare(number), id, owner);
    }

    @Transactional(readOnly = true)
    public Prepared prepare(long number) {
        var record = orders.open(number);
        var client = record.client();
        CustomerDtos.Ref ref = null;
        if (record.customerId() != null) {
            var customer = customers.snapshot(record.customerId());
            if (customer.active()) ref = new CustomerDtos.Ref(customer.id(), customer.version());
        }
        var items = record.items().stream().map(item -> new ItemInput(item.variantId(), item.quantity())).toList();
        var lookup = catalog.findVariants(items.stream().map(ItemInput::variantId).toList());
        var warnings = record.items().stream().filter(item -> !lookup.containsKey(item.variantId())
            || !lookup.get(item.variantId()).active()).map(item ->
                item.model() + " " + item.weight() + " g · cor " + item.color()).toList();
        return new Prepared(initial(new QuoteDtos.Client(client.name(), client.phone(), null, null), items, "", ref), warnings);
    }

    Copied save(Prepared prepared, UUID id, String owner) {
        var result = drafts.save(id, owner, new Save(null, prepared.content()));
        return new Copied(result.id(), result.quantity(), result.combinations(), prepared.unavailable());
    }

    static Content initial(QuoteDtos.Client client, List<ItemInput> items, String notes, CustomerDtos.Ref customer) {
        return new Content(client, List.copyOf(items), notes, new Editor(null, null, "1", null, null, List.of()),
            List.of(), customer, Source.IA);
    }
}

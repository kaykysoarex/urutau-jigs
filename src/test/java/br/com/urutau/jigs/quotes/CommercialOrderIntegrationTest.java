
package br.com.urutau.jigs.quotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import br.com.urutau.jigs.catalog.CatalogService;
import br.com.urutau.jigs.catalog.CatalogDtos.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:commercial_orders;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "urutau.admin.primary.username=primary-test",
    "urutau.admin.primary.password=primary-test-password",
    "urutau.admin.secondary.username=secondary-test",
    "urutau.admin.secondary.password=secondary-test-password"
})
@AutoConfigureMockMvc
class CommercialOrderIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired CatalogService catalog;
    @Autowired QuoteService quotes;

    @Test
    void confirmationPreservesOriginalAmountsAndAuditsTermsAndCancellation() throws Exception {
        var product = catalog.create(new ProductUpdate("Modelo comercial QA", true, List.of(new VariantUpdate(12, "QA", true))));
        var variant = product.variants().getFirst().id();
        price(variant, "26");
        var original = issue("Cliente Pinheiro", variant);
        price(variant, "99");
        var path = "/api/orders/" + original.number();
        mvc.perform(post(path + "/confirm").with(user("admin-qa").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        var confirmed = parse(confirm(path).andExpect(status().isOk())
            .andExpect(jsonPath("$.commercial.status").value("CONFIRMADO"))
            .andExpect(jsonPath("$.commercial.total").value("130.00"))
            .andExpect(jsonPath("$.items[0].unitPrice").value("26.00"))
            .andExpect(jsonPath("$.confirmedAt").isNotEmpty())
            .andExpect(jsonPath("$.confirmedBy").value("admin-qa"))
            .andExpect(jsonPath("$.expectedOn").value("2030-01-10"))
            .andExpect(jsonPath("$.events[0].action").value("CONFIRMACAO")));
        long orderNumber = confirmed.get("commercial").get("orderNumber").asLong();
        String edit = """
            {"version":0,"terms":{"expectedOn":"2030-01-20","notes":"Combinado com o cliente"}}
            """;
        mvc.perform(put(path + "/terms").with(user("second-qa").roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(edit))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.events[0].actor").value("second-qa"))
            .andExpect(jsonPath("$.events[1].notes").value("Separar em caixas"));
        mvc.perform(put(path + "/terms").with(user("second-qa").roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(edit)).andExpect(status().isConflict());
        mvc.perform(get("/api/orders").param("status", "CONFIRMADO").param("search", "pinHEIRO")
            .with(user("admin-qa").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].orderNumber").value(orderNumber));
        mvc.perform(get("/api/orders").param("status", "CONFIRMADO").param("search", "%")
            .with(user("admin-qa").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        update(path + "/cancel", "{\"reason\":\"  \"}").andExpect(status().isBadRequest());
        update(path + "/cancel", "{\"reason\":\"Cliente solicitou o cancelamento\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.cancelledAt").isNotEmpty())
            .andExpect(jsonPath("$.cancelledBy").value("admin-qa"))
            .andExpect(jsonPath("$.cancellationReason").value("Cliente solicitou o cancelamento"))
            .andExpect(jsonPath("$.commercial.orderNumber").value(orderNumber))
            .andExpect(jsonPath("$.events[0].action").value("CANCELAMENTO"));
        confirm(path).andExpect(status().isConflict());
        mvc.perform(get("/api/quotes/" + original.number() + "/pdf").with(user("admin-qa").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF));
        var later = issue("Cliente Carvalho", variant);
        var other = parse(confirm("/api/orders/" + later.number()).andExpect(status().isOk()));
        assertThat(other.get("commercial").get("orderNumber").asLong()).isGreaterThan(orderNumber);
    }

    @Test
    void locksConcurrentConfirmationsAndRejectsCancelledQuotesAndDraftIdentifiers() throws Exception {
        long variant = catalog.list().stream().filter(model -> model.name().equals("Spinner")).findFirst()
            .orElseThrow().variants().getFirst().id();
        var eligible = issue("Cliente Concorrência", variant);
        var path = "/api/orders/" + eligible.number();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<JsonNode> submit = () -> {
                start.await();
                return parse(confirm(path).andExpect(status().isOk()));
            };
            var a = workers.submit(submit);
            var b = workers.submit(submit);
            start.countDown();
            var first = a.get(10, TimeUnit.SECONDS);
            assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(first.get("events").size()).isEqualTo(1);
        }
        var waiting = issue("Cliente Sem Confirmação", variant);
        var pendingPath = "/api/orders/" + waiting.number();
        update(pendingPath + "/cancel", "{\"reason\":\"Negociação encerrada\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.commercial.status").value("CANCELADO"))
            .andExpect(jsonPath("$.commercial.orderNumber").isEmpty());
        confirm(pendingPath).andExpect(status().isConflict());
        confirm("/api/orders/" + UUID.randomUUID()).andExpect(status().isBadRequest());
        confirm("/api/orders/" + Long.MAX_VALUE).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders").param("status", "CANCELADO").param("search", "#" + waiting.number())
            .with(user("admin-qa").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].quoteNumber").value(waiting.number()));
    }

    @Test
    void tracksDeliverySeparatelyFromConfirmationAndAuditsOnlyOnce() throws Exception {
        long variant = catalog.list().stream().filter(model -> model.name().equals("Spinner")).findFirst()
            .orElseThrow().variants().getFirst().id();
        long number = issue("Cliente Entrega QA", variant).number();
        String path = "/api/orders/" + number;
        mvc.perform(post(path + "/deliver").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(path + "/deliver").with(user("admin-qa").roles("ADMIN"))).andExpect(status().isForbidden());
        update(path + "/deliver", "{}").andExpect(status().isConflict());
        var confirmed = parse(confirm(path).andExpect(status().isOk())
            .andExpect(jsonPath("$.commercial.fulfillment").value("EM_PRODUCAO")));
        mvc.perform(get("/api/orders").param("fulfillment", "EM_PRODUCAO").param("search", "Cliente Entrega QA")
            .with(user("admin-qa").roles("ADMIN"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].quoteNumber").value(number));
        var start = new CountDownLatch(1);
        JsonNode delivered;
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<JsonNode> submit = () -> {
                start.await();
                return parse(update(path + "/deliver", "{}").andExpect(status().isOk()));
            };
            var a = workers.submit(submit);
            var b = workers.submit(submit);
            start.countDown();
            delivered = a.get(10, TimeUnit.SECONDS);
            assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(delivered);
        }
        assertThat(delivered.get("confirmedAt")).isEqualTo(confirmed.get("confirmedAt"));
        assertThat(delivered.get("confirmedBy")).isEqualTo(confirmed.get("confirmedBy"));
        assertThat(delivered.get("commercial").get("status").asString()).isEqualTo("CONFIRMADO");
        assertThat(delivered.get("commercial").get("fulfillment").asString()).isEqualTo("ENTREGUE");
        assertThat(delivered.get("commercial").get("deliveredAt").asString()).isNotBlank();
        assertThat(delivered.get("deliveredBy").asString()).isEqualTo("admin-qa");
        assertThat(delivered.get("events").size()).isEqualTo(2);
        assertThat(delivered.get("events").get(0).get("action").asString()).isEqualTo("ENTREGA");
        mvc.perform(get("/api/orders").param("fulfillment", "ENTREGUE").param("search", "Cliente Entrega QA")
            .with(user("admin-qa").roles("ADMIN"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].quoteNumber").value(number))
            .andExpect(jsonPath("$.counts.delivered").value(1));
        update(path + "/cancel", "{\"reason\":\"Cancelamento de teste\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.commercial.deliveredAt").isNotEmpty())
            .andExpect(jsonPath("$.commercial.fulfillment").isEmpty());
        update(path + "/deliver", "{}").andExpect(status().isConflict());
    }

    private void price(long id, String amount) {
        catalog.updatePrices(new PricesUpdate(List.of(new VariantPriceUpdate(id, new BigDecimal(amount), new BigDecimal("16")))));
    }

    private QuoteDtos.Summary issue(String client, long variant) {
        var items = List.of(new QuoteDtos.ItemInput(variant, 5));
        var request = new QuoteDtos.IssueRequest(new QuoteDtos.Client(client, null, null, null), items,
            UUID.randomUUID(), quotes.preview(items).reviewToken(), null, null);
        return quotes.issue(request, "issuer-qa");
    }

    private ResultActions confirm(String path) throws Exception {
        return update(path + "/confirm", "{\"expectedOn\":\"2030-01-10\",\"notes\":\"Separar em caixas\"}");
    }

    private ResultActions update(String path, String value) throws Exception {
        return mvc.perform(post(path).with(user("admin-qa").roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(value));
    }

    private JsonNode parse(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsByteArray());
    }
}

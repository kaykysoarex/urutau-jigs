package br.com.urutau.jigs.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import br.com.urutau.jigs.catalog.CatalogService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:production_fifo;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
    "urutau.admin.primary.username=primary-test", "urutau.admin.primary.password=primary-test-password",
    "urutau.admin.secondary.username=secondary-test", "urutau.admin.secondary.password=secondary-test-password",
    "urutau.ai.gemini.api-key="
})
@AutoConfigureMockMvc
class ProductionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired CatalogService catalog;

    @TestConfiguration
    static class CommercialClock {
        @Bean
        @Primary
        Clock fixedCommercialClock() {
            // It is still November 26 in São Paulo.
            return Clock.fixed(Instant.parse("2026-11-27T01:30:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void derivesFifoFromCommercialRecordsAndKeepsPositionsThroughFilteringAndTransitions() throws Exception {
        getQueue("").andExpect(jsonPath("$.indicators.orders").value(0))
            .andExpect(jsonPath("$.indicators.first").doesNotExist()).andExpect(jsonPath("$.content").isEmpty());
        var model = catalog.list().stream().filter(product -> product.name().equals("Urunaja")).findFirst().orElseThrow();
        long variant = model.variants().stream().filter(v -> v.weight() == 10 && v.color().equals("03")).findFirst().orElseThrow().id();

        long pending = issue("Orçamento pendente QA", variant, 1);
        long cancelled = issue("Pedido cancelado QA", variant, 1);
        long delivered = issue("Pedido entregue QA", variant, 1);
        confirm(cancelled, null);
        confirm(delivered, null);
        write(post("/api/orders/" + cancelled + "/cancel"), Map.of("reason", "Cancelamento fictício")).andExpect(status().isOk());
        write(post("/api/orders/" + delivered + "/deliver"), Map.of()).andExpect(status().isOk());

        long later = issue("Cliente com prazo próximo", variant, 11);
        long earlier = issue("Henrique por ordem original", variant, 7);
        long tied = issue("Cliente com vencimento hoje", variant, 5);
        long laterNumber = confirm(later, "2026-11-28");
        long earlierNumber = confirm(earlier, "2026-11-25");
        long tiedNumber = confirm(tied, "2026-11-26");
        setConfirmation(laterNumber, "2024-05-20 15:47:08.000010");
        setConfirmation(earlierNumber, "2024-05-20 15:47:08.000001");
        setConfirmation(tiedNumber, "2024-05-20 15:47:08.000001");
        String historicalConfirmation = result(read(get("/api/orders/" + tied))).path("confirmedAt").asString();

        var session = new MockHttpSession();
        long assistantQuote = issue("Cliente com prazo futuro", variant, 8);
        var proposed = message(session, "Confirme o orçamento número " + assistantQuote + ".");
        String approval = proposed.path("proposal").path("id").asString();
        assertThat(approval).isNotBlank();
        mvc.perform(post("/api/assistant/proposals/" + approval + "/decision").session(session)
            .with(user("first").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true}"))
            .andExpect(status().isOk());
        var assistantOrder = result(read(get("/api/orders/" + assistantQuote)));
        long assistantNumber = assistantOrder.path("commercial").path("orderNumber").asLong();
        write(put("/api/orders/" + assistantQuote + "/terms"), Map.of("version", assistantOrder.path("version").asLong(),
            "terms", Map.of("expectedOn", "2026-11-29", "notes", "Prazo comercial revisado"))).andExpect(status().isOk());
        for (int i = 1; i <= 11; i++) confirm(issue("Cliente de Paginação %02d".formatted(i), variant, 2), null);

        getQueue("").andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.indicators.orders").value(15))
            .andExpect(jsonPath("$.indicators.demandedUnits").value(53))
            .andExpect(jsonPath("$.indicators.overdue").value(1))
            .andExpect(jsonPath("$.indicators.upcoming").value(1))
            .andExpect(jsonPath("$.indicators.dueToday").value(1))
            .andExpect(jsonPath("$.indicators.first.orderNumber").value(earlierNumber))
            .andExpect(jsonPath("$.content[0].position").value(1))
            .andExpect(jsonPath("$.content[0].orderNumber").value(earlierNumber))
            .andExpect(jsonPath("$.content[1].orderNumber").value(tiedNumber))
            .andExpect(jsonPath("$.content[1].confirmedAt").value(historicalConfirmation))
            .andExpect(jsonPath("$.content[2].orderNumber").value(laterNumber))
            .andExpect(jsonPath("$.content[3].orderNumber").value(assistantNumber))
            .andExpect(jsonPath("$.content[3].confirmedAt").value("2026-11-27T01:30:00Z"))
            .andExpect(jsonPath("$.content.length()").value(12))
            .andExpect(jsonPath("$.pages").value(2));
        getQueue("?page=1").andExpect(jsonPath("$.content[0].position").value(13))
            .andExpect(jsonPath("$.content[2].position").value(15));
        getQueue("?deadline=PROXIMO").andExpect(jsonPath("$.content[0].position").value(3));
        getQueue("?deadline=VENCE_HOJE").andExpect(jsonPath("$.content[0].position").value(2));
        getQueue("?deadline=DENTRO_DO_PRAZO").andExpect(jsonPath("$.content[0].position").value(4));
        getQueue("?deadline=SEM_PRAZO").andExpect(jsonPath("$.matches").value(11));
        getQueue("?search=Urunaja").andExpect(jsonPath("$.matches").value(15));
        getQueue("?search=Cliente de Paginação 11").andExpect(jsonPath("$.content[0].position").value(15));
        getQueue("?search=" + "%05d".formatted(tiedNumber)).andExpect(jsonPath("$.content[0].position").value(2));
        getQueue("?search=Pedido cancelado QA").andExpect(jsonPath("$.matches").value(0));
        getQueue("?search=Orçamento pendente QA").andExpect(jsonPath("$.matches").value(0));
        assertThat(result(read(get("/api/orders/" + pending))).path("commercial").path("status").asString()).isEqualTo("PENDENTE");

        // Legacy agreements can have two lines for the same variant.
        jdbc.sql("""
            insert into commercial_quote_item (quote_id, variant_id, model_name, weight_grams, color_code, quantity, unit_price, subtotal)
            select quote_id, variant_id, model_name, weight_grams, color_code, 3, unit_price, unit_price * 3
            from commercial_quote_item where quote_id = :quote
            """).param("quote", earlier).update();
        jdbc.sql("update commercial_quote set total_quantity = 10, total = 270.00 where id = :quote").param("quote", earlier).update();
        jdbc.sql("update catalog_product set name = 'Nome Atual Fictício' where id = :model").param("model", model.id()).update();
        jdbc.sql("update catalog_variant set active = false where id = :variant").param("variant", variant).update();

        read(get("/api/production/" + earlierNumber)).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].model").value("Urunaja"))
            .andExpect(jsonPath("$.items[0].combination.productId").value(model.id()))
            .andExpect(jsonPath("$.items[0].combination.variantId").value(variant))
            .andExpect(jsonPath("$.items[0].combination.weight").value(10))
            .andExpect(jsonPath("$.items[0].combination.color").value("03"))
            .andExpect(jsonPath("$.items[0].units").value(10))
            .andExpect(jsonPath("$.items[0].available").value(false))
            .andExpect(jsonPath("$.order.total").value("270.00"))
            .andExpect(jsonPath("$.phone").value("(62) 99111-2222"));
        read(get("/api/orders/" + earlier)).andExpect(jsonPath("$.items.length()").value(2));
        assertThat(message(session, "Quais pedidos estão na fila de produção?").path("text").asString()).contains("56 iscas demandadas");
        assertThat(message(session, "Qual é o primeiro pedido da fila?").path("cards").get(0).path("title").asString())
            .contains("Henrique por ordem original");
        assertThat(message(session, "Mostre os detalhes de fabricação do primeiro pedido.").toString())
            .contains("Urunaja", "10 unidades", "desativada");
        assertThat(message(session, "Quais pedidos estão atrasados?").path("cards").get(0).path("title").asString())
            .contains("Henrique por ordem original");
        assertThat(message(session, "Quais pedidos possuem prazo para hoje?").path("cards").get(0).path("title").asString())
            .contains("Cliente com vencimento hoje");
        assertThat(message(session, "Abra a fila de produção.").toString()).contains("Abrir Produção", "producao");
        assertThat(message(session, "Mostre o pedido de produção do cliente Henrique.").toString()).contains("Urunaja", "10 unidades");
        assertThat(message(session, "Mostre o pedido de produção do cliente Cliente de Paginação.").path("text").asString())
            .contains("mais de um pedido");
        var unavailable = message(session, "Registre a fabricação de 10 unidades.");
        assertThat(unavailable.path("text").asString()).contains("ainda não está disponível");
        assertThat(unavailable.path("proposal").isNull()).isTrue();

        write(post("/api/orders/" + earlier + "/cancel"), Map.of("reason", "Cancelamento fictício de produção"))
            .andExpect(status().isOk());
        write(post("/api/orders/" + tied + "/deliver"), Map.of()).andExpect(status().isOk());
        getQueue("").andExpect(jsonPath("$.indicators.orders").value(13))
            .andExpect(jsonPath("$.indicators.first.orderNumber").value(laterNumber))
            .andExpect(jsonPath("$.content[0].position").value(1));
        read(get("/api/production/" + earlierNumber)).andExpect(status().isNotFound());
        read(get("/api/production/" + tiedNumber)).andExpect(status().isNotFound());
        read(get("/api/orders/" + tied)).andExpect(jsonPath("$.confirmedAt").value(historicalConfirmation))
            .andExpect(jsonPath("$.commercial.fulfillment").value("ENTREGUE"));
    }

    @Test
    void restrictsProductionToAdministratorsAndValidatesInputs() throws Exception {
        mvc.perform(get("/api/production")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/production/1").with(user("visitor").roles("USER"))).andExpect(status().isForbidden());
        read(get("/api/production/0")).andExpect(status().isBadRequest());
        read(get("/api/production").param("deadline", "INVENTADO")).andExpect(status().isBadRequest());
        read(get("/api/production").param("page", "-1")).andExpect(status().isBadRequest());
        read(get("/api/production").param("search", "X".repeat(121))).andExpect(status().isBadRequest());
    }

    private long issue(String client, long variant, int quantity) throws Exception {
        var items = List.of(Map.of("variantId", variant, "quantity", quantity));
        var estimate = result(write(post("/api/quotes/preview"), Map.of("items", items)));
        return result(write(post("/api/quotes"), Map.of("client", Map.of("name", client, "phone", "(62) 99111-2222"),
            "items", items, "requestId", UUID.randomUUID(), "reviewToken", estimate.path("reviewToken").asString()))
            .andExpect(status().isCreated())).path("number").asLong();
    }

    private long confirm(long quote, String deadline) throws Exception {
        return result(write(post("/api/orders/" + quote + "/confirm"),
            deadline == null ? Map.of() : Map.of("expectedOn", deadline))).path("commercial").path("orderNumber").asLong();
    }

    private void setConfirmation(long number, String timestamp) {
        jdbc.sql("update commercial_order set confirmed_at = :at where number = :number")
            .param("at", java.time.LocalDateTime.parse(timestamp.replace(' ', 'T'))).param("number", number).update();
    }

    private JsonNode message(MockHttpSession session, String text) throws Exception {
        var rows = result(write(post("/api/assistant/messages").session(session), Map.of("section", "OVERVIEW", "message", text)));
        return rows.get(rows.size() - 1);
    }

    private ResultActions getQueue(String query) throws Exception {
        return read(get("/api/production" + query)).andExpect(status().isOk());
    }

    private ResultActions read(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(user("second-device").roles("ADMIN")));
    }

    private ResultActions write(MockHttpServletRequestBuilder request, Map<?, ?> body) throws Exception {
        return mvc.perform(request.with(user("first").roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode result(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsByteArray());
    }
}

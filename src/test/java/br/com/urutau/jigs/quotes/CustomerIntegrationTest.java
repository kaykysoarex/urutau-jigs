
package br.com.urutau.jigs.quotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import br.com.urutau.jigs.catalog.CatalogService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:customers;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "urutau.admin.primary.username=primary-test", "urutau.admin.primary.password=primary-test-password",
    "urutau.admin.secondary.username=secondary-test", "urutau.admin.secondary.password=secondary-test-password"
})
@AutoConfigureMockMvc
class CustomerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired CatalogService catalog;
    @Autowired QuoteService quotes;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void normalizesTelephoneAndRequiresExplicitSharingWithoutMergingNames() throws Exception {
        mvc.perform(get("/api/customers/1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/customers").with(user("first").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input("Protegido", "", false))))
                .andExpect(status().isForbidden());
        var first = create("Cliente Paraná", "(41) 99990-0101");
        long id = first.get("id").asLong();
        assertThat(first.get("whatsapp").asString()).isEqualTo("5541999900101");
        submit(post("/api/customers"), input("Outra loja", "0055 41 99990-0101", false)).andExpect(status().isConflict());
        submit(post("/api/customers/phone-check"), Map.of("phone", "+55 (41) 99990-0101"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        submit(post("/api/customers"), input("Outra loja", "0055 41 99990-0101", true)).andExpect(status().isOk());
        assertThat(create("Cliente Paraná", "").get("id").asLong()).isNotEqualTo(id);
        submit(post("/api/customers"), input("Inválido", "123", false)).andExpect(status().isBadRequest());
        submit(post("/api/customers/search"), Map.of("search", "cliente paraná", "page", 0))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2));
        submit(post("/api/customers/search"), Map.of("search", "Curitiba", "page", 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        String path = "/api/customers/" + id;
        submit(put(path + "/active"), Map.of("version", 0, "active", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        submit(post("/api/customers/matches"), Map.of("search", "99990-0101"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        submit(put(path + "/active"), Map.of("version", 1, "active", true)).andExpect(status().isOk());
        assertThat(jdbc.queryForList("select action from customer_audit_event where customer_id = ? order by id",
                String.class, id)).containsExactly("CADASTRO", "DESATIVACAO", "REATIVACAO");
    }

    @Test
    void restoresAnOwnedDraftWithTheOriginalDataAndAnUpdatedRegistryVersion() throws Exception {
        long id = create("Nome Original Rascunho", "").get("id").asLong();
        String path = "/api/quote-drafts/" + UUID.randomUUID();
        var client = new QuoteDtos.Client("Nome Original Rascunho", null, "Curitiba", "PR");
        var content = new QuoteDraftDtos.Content(client, List.of(), "", new QuoteDraftDtos.Editor(null, null, "1",
                null, null, List.of()), List.of(), new CustomerDtos.Ref(id, 0L));
        submit(put(path), new QuoteDraftDtos.Save(null, content)).andExpect(status().isOk());
        submit(put("/api/customers/" + id), new CustomerDtos.Change(0L, input("Nome Novo Rascunho", "", false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(get(path).with(user("first").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.content.client.name").value("Nome Original Rascunho"))
                .andExpect(jsonPath("$.content.customer.id").value(id))
                .andExpect(jsonPath("$.customer.name").value("Nome Novo Rascunho"))
                .andExpect(jsonPath("$.customer.version").value(1));
        mvc.perform(get(path).with(user("second").roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/customers/" + id).with(user("second").roles("ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void linksHistoricalDocumentsAndRollsUpOnlyConfirmedOrdersWithoutChangingThePdf() throws Exception {
        var inline = issue("Cliente de Emissão", new CustomerDtos.Selection(null, true, false));
        var inlineDetail = parse(mvc.perform(get("/api/orders/" + inline).with(user("first").roles("ADMIN"))));
        assertThat(inlineDetail.get("customerId").asLong()).isPositive();
        long id = create("Nome do Cadastro", "").get("id").asLong();
        long number = issue("Nome Histórico no PDF", null);
        String order = "/api/orders/" + number;
        submit(put(order + "/customer"), new CustomerDtos.Selection(id, false, false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events[0].action").value("VINCULO"));
        mvc.perform(get("/api/customers/" + id).with(user("first").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.indicators.pending").value(1))
                .andExpect(jsonPath("$.indicators.sales.orders").value(0));
        submit(post(order + "/confirm"), Map.of()).andExpect(status().isOk());
        submit(post(order + "/deliver"), Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.commercial.fulfillment").value("ENTREGUE"));
        long other = issue("Segundo pedido", null);
        submit(post("/api/orders/" + other + "/confirm"), Map.of("customer", Map.of("id", id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerId").value(id));
        submit(post("/api/orders/" + other + "/cancel"), Map.of("reason", "Cancelamento QA"))
                .andExpect(status().isOk());
        String customer = "/api/customers/" + id;
        mvc.perform(get(customer).with(user("first").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.indicators.quotes").value(2)).andExpect(jsonPath("$.indicators.sales.orders").value(1))
                .andExpect(jsonPath("$.indicators.sales.units").value(5))
                .andExpect(jsonPath("$.indicators.sales.approvedTotal").value("130.00"))
                .andExpect(jsonPath("$.indicators.lastOrderAt").isNotEmpty());
        mvc.perform(get(customer + "/history").param("orders", "true").with(user("first").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].status").value("CANCELADO"))
                .andExpect(jsonPath("$.content[1].fulfillment").value("ENTREGUE"));
        submit(put(customer), new CustomerDtos.Change(0L, input("Nome Atualizado", "", false))).andExpect(status().isOk());
        byte[] pdf = mvc.perform(get("/api/quotes/" + number + "/pdf").with(user("first").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(document)).contains("Nome Histórico no PDF").doesNotContain("Nome Atualizado");
        }
    }

    private long issue(String name, CustomerDtos.Selection selection) {
        long variant = catalog.list().stream().filter(p -> p.name().equals("Spinner")).findFirst().orElseThrow().variants().getFirst().id();
        var items = List.of(new QuoteDtos.ItemInput(variant, 5));
        return quotes.issue(new QuoteDtos.IssueRequest(new QuoteDtos.Client(name, null, "Curitiba", "PR"), items,
                UUID.randomUUID(), quotes.preview(items).reviewToken(), null, null, selection), "first").number();
    }

    private JsonNode create(String name, String phone) throws Exception {
        return parse(submit(post("/api/customers"), input(name, phone, false)).andExpect(status().isOk()));
    }

    private static CustomerDtos.Input input(String name, String phone, boolean allowShared) {
        return new CustomerDtos.Input(name, null, phone, null, "Curitiba", "PR", null, allowShared);
    }

    private ResultActions submit(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mvc.perform(request.with(user("first").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }

    private JsonNode parse(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }
}

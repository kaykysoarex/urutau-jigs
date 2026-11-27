
package br.com.urutau.jigs.quotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.urutau.jigs.catalog.CatalogDtos.PricesUpdate;
import br.com.urutau.jigs.catalog.CatalogDtos.ProductUpdate;
import br.com.urutau.jigs.catalog.CatalogDtos.Settings;
import br.com.urutau.jigs.catalog.CatalogDtos.VariantPriceUpdate;
import br.com.urutau.jigs.catalog.CatalogDtos.VariantUpdate;
import br.com.urutau.jigs.catalog.CatalogService;
import br.com.urutau.jigs.quotes.QuoteDraftDtos.Content;
import br.com.urutau.jigs.quotes.QuoteDraftDtos.Editor;
import br.com.urutau.jigs.quotes.QuoteDraftDtos.Save;
import br.com.urutau.jigs.quotes.QuoteDraftDtos.Selection;
import br.com.urutau.jigs.quotes.QuoteDtos.Client;
import br.com.urutau.jigs.quotes.QuoteDtos.IssueRequest;
import br.com.urutau.jigs.quotes.QuoteDtos.ItemInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:quote_draft_tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "urutau.admin.primary.username=primary-test",
        "urutau.admin.primary.password=primary-test-password",
        "urutau.admin.secondary.username=secondary-test",
        "urutau.admin.secondary.password=secondary-test-password"
})
@AutoConfigureMockMvc
class QuoteDraftIntegrationTest {
    private static final String DRAFTS = "/api/quote-drafts";
    private static final Client CLIENT = new Client("Cliente fictício", "11999990000", "São Paulo", "SP");
    private static final Editor EMPTY_EDITOR = new Editor(null, null, "1", null, null, List.of());

    @Autowired MockMvc mvc;
    @Autowired CatalogService catalog;
    @Autowired QuoteService quotes;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate db;

    private long model;
    private long variant;
    private String modelName;

    @BeforeEach
    void fixtures() {
        catalog.updateSettings(new Settings(30));
        modelName = "Modelo de rascunho " + UUID.randomUUID();
        var product = catalog.create(new ProductUpdate(modelName, true, List.of(new VariantUpdate(12, "03", true))));
        model = product.id();
        variant = product.variants().getFirst().id();
        prices("26.00", "16.00");
    }

    @Test
    void recoversMultipleDraftsOnlyForTheirOwnerAndRejectsWritesWithoutCsrf() throws Exception {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var content = new Content(CLIENT, List.of(new ItemInput(variant, 5)), "Separar as cores.",
                new Editor(model, 12, "5", null, null, List.of(new Selection(variant, ""))),
                List.of(new ItemInput(variant, 8)));
        mvc.perform(get(DRAFTS)).andExpect(status().isUnauthorized());
        mvc.perform(put(path(first)).with(user("owner").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Save(null, content))))
                .andExpect(status().isForbidden());
        save(first, "owner", null, content).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.estimatedTotal").value("130.00"));
        save(second, "owner", null, content).andExpect(status().isOk());
        mvc.perform(get(DRAFTS).with(user("owner").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].id").value(second.toString()));
        open(first, "owner").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.content.notes").value("Separar as cores."))
                .andExpect(jsonPath("$.content.editor.colors[0].quantity").value(""))
                .andExpect(jsonPath("$.content.pendingAi[0].quantity").value(8));
        mvc.perform(get(DRAFTS).with(user("other").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        open(first, "other").andExpect(status().isNotFound());
        save(first, "other", 0L, content).andExpect(status().isNotFound());
        save(first, "other", null, content).andExpect(status().isNotFound());
        discard(first, "other", 0).andExpect(status().isNotFound());
    }

    @Test
    void validatesEmptyCreationAndInvalidFieldsAndPreservesTheLatestVersionOnConflict() throws Exception {
        var id = UUID.randomUUID();
        var empty = new Content(new Client(null, null, null, null), List.of(), "", EMPTY_EDITOR, List.of());
        save(id, "editor", null, empty).andExpect(status().isBadRequest());
        save(id, "editor", null, draft(new ItemInput(variant, 0))).andExpect(status().isBadRequest());
        save(id, "editor", null, draft(new ItemInput(Long.MAX_VALUE, 5))).andExpect(status().isBadRequest());
        var original = draft(new ItemInput(variant, 5));
        var changed = draft(new ItemInput(variant, 10));
        save(id, "editor", null, original).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
        save(id, "editor", null, original).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
        save(id, "editor", 0L, changed).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        save(id, "editor", 0L, original).andExpect(status().isConflict());
        discard(id, "editor", 0).andExpect(status().isConflict());
        open(id, "editor").andExpect(status().isOk()).andExpect(jsonPath("$.content.items[0].quantity").value(10));
        discard(id, "editor", 1).andExpect(status().isNoContent());
        open(id, "editor").andExpect(status().isNotFound());
        save(id, "editor", null, original).andExpect(status().isNotFound());
        assertPayloadErased(id);
    }

    @Test
    void repricesRestoredDraftsAndKeepsUnavailableCombinationsVisible() throws Exception {
        var id = UUID.randomUUID();
        save(id, "prices", null, draft(new ItemInput(variant, 29))).andExpect(status().isOk())
                .andExpect(jsonPath("$.estimatedTotal").value("754.00"));
        prices("28.00", "18.00");
        open(id, "prices").andExpect(status().isOk()).andExpect(jsonPath("$.estimateChanged").value(true))
                .andExpect(jsonPath("$.estimate.total").value("812.00"));
        catalog.updateSettings(new Settings(29));
        open(id, "prices").andExpect(status().isOk()).andExpect(jsonPath("$.estimateChanged").value(true))
                .andExpect(jsonPath("$.estimate.priceTier").value("LOJISTA")).andExpect(jsonPath("$.estimate.total").value("522.00"));
        catalog.update(model, new ProductUpdate(modelName, true, List.of(new VariantUpdate(12, "03", false))));
        open(id, "prices").andExpect(status().isOk()).andExpect(jsonPath("$.estimate").isEmpty())
                .andExpect(jsonPath("$.content.items[0].quantity").value(29))
                .andExpect(jsonPath("$.variants[0].active").value(false));
    }

    @Test
    void failedIssueKeepsDraftAndConcurrentRetriesProduceOnlyOneQuote() throws Exception {
        var id = UUID.randomUUID();
        var requestId = UUID.randomUUID();
        var content = draft(new ItemInput(variant, 5));
        save(id, "issuer", null, content).andExpect(status().isOk());
        var stale = new IssueRequest(CLIENT, content.items(), requestId, "a".repeat(64), id, 0L);
        issue(stale).andExpect(status().isConflict());
        open(id, "issuer").andExpect(status().isOk()).andExpect(jsonPath("$.content.items[0].quantity").value(5));

        var request = new IssueRequest(CLIENT, content.items(), requestId,
                quotes.preview(content.items()).reviewToken(), id, 0L);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var issueOnce = (java.util.concurrent.Callable<JsonNode>) () -> {
                start.await();
                return result(issue(request).andExpect(status().isCreated()));
            };
            var first = executor.submit(issueOnce);
            var second = executor.submit(issueOnce);
            start.countDown();
            var issued = first.get(15, TimeUnit.SECONDS);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(issued);
            assertThat(issued.get("total").asString()).isEqualTo("130.00");
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM commercial_quote WHERE request_id = ?",
                Integer.class, requestId.toString())).isEqualTo(1);
        open(id, "issuer").andExpect(status().isNotFound());
        save(id, "issuer", 0L, content).andExpect(status().isNotFound());
        save(id, "issuer", null, content).andExpect(status().isNotFound());
        assertPayloadErased(id);
    }

    private Content draft(ItemInput item) {
        return new Content(CLIENT, List.of(item), "", EMPTY_EDITOR, List.of());
    }

    private void prices(String retail, String wholesale) {
        catalog.updatePrices(new PricesUpdate(List.of(
                new VariantPriceUpdate(variant, new BigDecimal(retail), new BigDecimal(wholesale)))));
    }

    private void assertPayloadErased(UUID id) {
        assertThat(db.queryForObject("SELECT content FROM quote_draft WHERE id = ?", String.class, id.toString())).isNull();
    }

    private static String path(UUID id) {
        return DRAFTS + "/" + id;
    }

    private ResultActions save(UUID id, String owner, Long version, Content content) throws Exception {
        return mvc.perform(put(path(id)).with(user(owner).roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Save(version, content))));
    }

    private ResultActions open(UUID id, String owner) throws Exception {
        return mvc.perform(get(path(id)).with(user(owner).roles("ADMIN")));
    }

    private ResultActions discard(UUID id, String owner, long version) throws Exception {
        return mvc.perform(delete(path(id)).param("version", Long.toString(version)).with(user(owner).roles("ADMIN")).with(csrf()));
    }

    private ResultActions issue(IssueRequest request) throws Exception {
        return mvc.perform(post("/api/quotes").with(user("issuer").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)));
    }

    private JsonNode result(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsString());
    }
}

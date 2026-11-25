package br.com.urutau.jigs.quotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.urutau.jigs.catalog.CatalogDtos.PricesUpdate;
import br.com.urutau.jigs.catalog.CatalogDtos.ProductUpdate;
import br.com.urutau.jigs.catalog.CatalogDtos.Settings;
import br.com.urutau.jigs.catalog.CatalogDtos.VariantPriceUpdate;
import br.com.urutau.jigs.catalog.CatalogDtos.VariantUpdate;
import br.com.urutau.jigs.catalog.CatalogService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:quote_tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "urutau.admin.primary.username=primary-test",
        "urutau.admin.primary.password=primary-test-password",
        "urutau.admin.secondary.username=secondary-test",
        "urutau.admin.secondary.password=secondary-test-password"
})
@AutoConfigureMockMvc
class QuoteIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired CatalogService catalog;
    @Autowired JsonMapper json;
    @Autowired QuoteService quoteService;

    private long first;
    private long second;
    private long otherModel;
    private long unpriced;
    private long inactive;

    @BeforeEach
    void createFixtures() {
        catalog.updateSettings(new Settings(30));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var model = catalog.create(new ProductUpdate("Modelo de teste " + suffix, true, List.of(
                new VariantUpdate(10, "01", true), new VariantUpdate(12, "03", true),
                new VariantUpdate(12, "04", true), new VariantUpdate(14, "06", false))));
        var other = catalog.create(new ProductUpdate("Outro modelo " + suffix, true,
                List.of(new VariantUpdate(16, "08", true))));
        first = model.variants().get(0).id();
        second = model.variants().get(1).id();
        unpriced = model.variants().get(2).id();
        inactive = model.variants().get(3).id();
        otherModel = other.variants().getFirst().id();
        catalog.updatePrices(new PricesUpdate(List.of(
                price(first, "10.00", "7.00"), price(second, "20.00", "15.00"), price(otherModel, "25.00", "18.00"))));
    }

    @Test
    void choosesTierAt29And30AndRepricesEveryCombinationWhenQuantityFalls() throws Exception {
        preview(List.of(item(first, 10), item(second, 10), item(otherModel, 9)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(29))
                .andExpect(jsonPath("$.priceTier").value("CONSUMIDOR_FINAL"))
                .andExpect(jsonPath("$.remainingForWholesale").value(1)).andExpect(jsonPath("$.total").value("525.00"));
        preview(List.of(item(first, 10), item(second, 10), item(otherModel, 10)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(30))
                .andExpect(jsonPath("$.priceTier").value("LOJISTA")).andExpect(jsonPath("$.remainingForWholesale").value(0))
                .andExpect(jsonPath("$.items[0].unitPrice").value("7.00")).andExpect(jsonPath("$.items[1].unitPrice").value("15.00"))
                .andExpect(jsonPath("$.items[2].unitPrice").value("18.00")).andExpect(jsonPath("$.total").value("400.00"));
        preview(List.of(item(first, 10), item(second, 10), item(otherModel, 11)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(31)).andExpect(jsonPath("$.total").value("418.00"));
        preview(List.of(item(first, 10), item(second, 10)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.priceTier").value("CONSUMIDOR_FINAL"))
                .andExpect(jsonPath("$.remainingForWholesale").value(10)).andExpect(jsonPath("$.total").value("300.00"));
    }

    @Test
    void appliesConfiguredThresholdAndCombinesDuplicateVariants() throws Exception {
        catalog.updateSettings(new Settings(8));
        preview(List.of(item(first, 4), item(first, 4))).andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(8)).andExpect(jsonPath("$.wholesaleThreshold").value(8))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].quantity").value(8))
                .andExpect(jsonPath("$.total").value("56.00"));
    }

    @Test
    void rejectsUnknownAndInactiveCombinationsAndInvalidQuantities() throws Exception {
        preview(List.of(item(Long.MAX_VALUE, 2))).andExpect(status().isBadRequest());
        preview(List.of(item(inactive, 2))).andExpect(status().isBadRequest());
        preview(List.of(item(first, -1))).andExpect(status().isBadRequest());
        preview(List.of(item(first, 0))).andExpect(status().isBadRequest());
        preview(List.of(item(first, 100001))).andExpect(status().isBadRequest());
        preview(List.of(item(first, 2.7))).andExpect(status().isBadRequest());
    }

    @Test
    void blocksMissingPricesAndPricesChangedSinceReview() throws Exception {
        preview(List.of(item(unpriced, 1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.missingPrices").value(1)).andExpect(jsonPath("$.total").isEmpty());
        var missing = List.of(item(unpriced, 1));
        issue(missing, UUID.randomUUID(), review(missing)).andExpect(status().isConflict());
        var changed = List.of(item(first, 1), item(second, 1));
        String token = review(changed);
        catalog.updatePrices(new PricesUpdate(List.of(price(first, "12.00", "7.00"), price(second, "18.00", "15.00"))));
        preview(changed).andExpect(jsonPath("$.total").value("30.00"));
        issue(changed, UUID.randomUUID(), token).andExpect(status().isConflict());
        preview(List.of()).andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(0));
    }

    @Test
    void previewPdfUsesCurrentPricesAndLeavesHistoryAndQuoteNumberUntouched() throws Exception {
        var priced = List.of(item(first, 1));
        String token = review(priced);
        var firstIssued = issue(priced, UUID.randomUUID(), token).andExpect(status().isCreated()).andReturn();
        long number = json.readTree(firstIssued.getResponse().getContentAsString()).path("number").asLong();
        long before = quoteService.history(0, 20).totalElements();

        var partial = List.of(item(first, 20), item(second, 8), item(unpriced, 2));
        byte[] pdf = previewPdf(Map.of("items", partial, "client", Map.of("city", "Goiânia", "state", "go")), false)
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", startsWith("inline")))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().doesNotExist("Location"))
                .andReturn().getResponse().getContentAsByteArray();
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(document))
                    .contains("PRÉVIA — SEM VALIDADE COMERCIAL", "A definir", "Goiânia", "7,00", "140,00")
                    .doesNotContain("Nº " + String.format(java.util.Locale.ROOT, "%05d", number));
        }
        previewPdf(Map.of("items", partial), true).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("previa-orcamento-urutau.pdf")));

        issue(partial, UUID.randomUUID(), review(partial)).andExpect(status().isConflict());
        assertThat(quoteService.history(0, 20).totalElements()).isEqualTo(before);
        issue(priced, UUID.randomUUID(), token).andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(number + 1));
    }

    @Test
    void previewPdfChecksCatalogInputsAndAcceptsAnUnfilledClientForTheDraftOnly() throws Exception {
        var priced = List.of(item(first, 2));
        var blankClient = Map.of("name", "   ");
        previewPdf(Map.of("items", priced, "client", blankClient), false).andExpect(status().isOk());
        previewPdf(Map.of("items", priced, "client", Map.of("state", "XX")), false).andExpect(status().isBadRequest());
        previewPdf(Map.of("items", List.of()), false).andExpect(status().isBadRequest());
        previewPdf(Map.of("items", List.of(item(inactive, 1))), false).andExpect(status().isBadRequest());
        previewPdf(Map.of("items", List.of(item(first, -1))), false).andExpect(status().isBadRequest());
        issue(priced, UUID.randomUUID(), review(priced), blankClient).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Informe o nome do cliente."));
    }

    @Test
    void keepsAnIssuedQuoteAndCommercialPdfStableAfterCatalogEdits() throws Exception {
        UUID requestId = UUID.randomUUID();
        var items = List.of(item(first, 1));
        String token = review(items);
        String response = issue(items, requestId, token).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDENTE")).andReturn().getResponse().getContentAsString();
        long number = json.readTree(response).path("number").asLong();
        catalog.updatePrices(new PricesUpdate(List.of(price(first, "88.00", "80.00"))));
        var fixture = catalog.list().stream().filter(model -> model.variants().stream().anyMatch(v -> v.id() == first))
                .findFirst().orElseThrow();
        catalog.update(fixture.id(), new ProductUpdate("Nome atualizado " + number, false, List.of()));
        issue(items, requestId, token).andExpect(status().isCreated()).andExpect(jsonPath("$.number").value(number));
        var document = quoteService.document(number);
        assertThat(document.lines().getFirst().unitPrice()).isEqualByComparingTo("10.00");
        assertThat(document.lines().getFirst().model()).isEqualTo(fixture.name());
        mvc.perform(get("/api/quotes").with(user("secondary-test").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].total").value("10.00"))
                .andExpect(jsonPath("$.content[0].status").value("PENDENTE"));
        var pdf = mvc.perform(get("/api/quotes/{number}/pdf", number).with(user("primary-test").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")))
                .andReturn().getResponse().getContentAsByteArray();
        try (var loaded = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(loaded)).contains("Cliente de teste", "10,00");
        }
        mvc.perform(get("/api/quotes/{number}/pdf?download=true", number).with(user("primary-test").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
    }

    @Test
    void requiresAuthenticationAndCsrfAndAllowsEachConfiguredAdmin() throws Exception {
        mvc.perform(get("/api/quotes")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/quotes/preview").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}")
                .with(user("primary-test").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/quotes/preview/pdf").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}")
                .with(user("primary-test").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/quotes/preview/pdf").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}")
                .with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(formLogin("/login").user("primary-test").password("primary-test-password"))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location", "/"));
        mvc.perform(formLogin("/login").user("secondary-test").password("secondary-test-password"))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location", "/"));
        mvc.perform(formLogin("/login").user("primary-test").password("incorrect-password"))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location", "/login.html?erro"));
    }

    private ResultActions preview(List<Map<String, Object>> items) throws Exception {
        return mvc.perform(post("/api/quotes/preview").with(user("primary-test").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("items", items))));
    }

    private ResultActions previewPdf(Map<String, ?> request, boolean download) throws Exception {
        return mvc.perform(post("/api/quotes/preview/pdf").param("download", Boolean.toString(download))
                .with(user("primary-test").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)));
    }

    private String review(List<Map<String, Object>> items) throws Exception {
        var result = preview(items).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("reviewToken").asString();
    }

    private ResultActions issue(List<Map<String, Object>> items, UUID requestId, String token) throws Exception {
        return issue(items, requestId, token, Map.of("name", "Cliente de teste", "state", "GO"));
    }

    private ResultActions issue(List<Map<String, Object>> items, UUID requestId, String token,
            Map<String, String> client) throws Exception {
        var request = Map.of("items", items, "client", client,
                "requestId", requestId, "reviewToken", token);
        return mvc.perform(post("/api/quotes").with(user("primary-test").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)));
    }

    private static Map<String, Object> item(long variantId, Number quantity) {
        return Map.of("variantId", variantId, "quantity", quantity);
    }

    private static VariantPriceUpdate price(long variant, String retail, String wholesale) {
        return new VariantPriceUpdate(variant, new BigDecimal(retail), new BigDecimal(wholesale));
    }
}

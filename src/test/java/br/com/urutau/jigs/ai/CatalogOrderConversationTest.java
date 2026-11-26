package br.com.urutau.jigs.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.urutau.jigs.ai.AiDtos.ConversationView;
import br.com.urutau.jigs.ai.AiDtos.ReviewItem;
import br.com.urutau.jigs.ai.AiInterpreter.Input;
import br.com.urutau.jigs.ai.AiInterpreter.ProposedItem;
import br.com.urutau.jigs.ai.AiInterpreter.Result;
import br.com.urutau.jigs.catalog.CatalogDtos.PricesUpdate;
import br.com.urutau.jigs.catalog.CatalogDtos.VariantPriceUpdate;
import br.com.urutau.jigs.catalog.CatalogService;
import br.com.urutau.jigs.quotes.QuoteDtos.ItemInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:catalog_order_chat;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "urutau.admin.primary.username=primary-test",
    "urutau.admin.primary.password=primary-test-password",
    "urutau.admin.secondary.username=secondary-test",
    "urutau.admin.secondary.password=secondary-test-password"
})
@AutoConfigureMockMvc
class CatalogOrderConversationTest {
    // Matches the message produced by the official catalog's WhatsApp button.
    private static final String CATALOG_MESSAGE = """
        🎣 *Pedido Urutau Jigs*
        ─────────────────────
        • Jig Peixinho | 12g | Cor 05 | Qtd: 5
        • Urunaja | 10g | Cor 01 | Qtd: 5
        • Urunaja | 10g | Cor 02 | Qtd: 5
        ─────────────────────
        *Total: 15 unidades*
        """;

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired CatalogService catalog;
    @MockitoBean AiProviderSelection provider;

    private final MockHttpSession session = new MockHttpSession();

    @Test
    void importsTheOfficialWhatsAppMessageWithoutGeminiAndLeavesReviewToTheAdmin() throws Exception {
        var draft = sendMessage(startConversation(), CATALOG_MESSAGE);

        assertThat(draft.needsClarification()).isFalse();
        assertThat(draft.questions()).isEmpty();
        assertThat(draft.items()).extracting(ReviewItem::model, ReviewItem::weight, ReviewItem::color, ReviewItem::quantity)
            .containsExactly(tuple("Jig Peixinho", 12, "05", 5), tuple("Urunaja", 10, "01", 5),
                tuple("Urunaja", 10, "02", 5));
        verify(provider, never()).interpret(any());

        previewDraft(draft)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.quantity").value(15))
            .andExpect(jsonPath("$.missingPrices").value(0))
            .andExpect(jsonPath("$.priceTier").value("CONSUMIDOR_FINAL"))
            .andExpect(jsonPath("$.items[0].unitPrice").value("26.00"))
            .andExpect(jsonPath("$.items[1].unitPrice").value("27.00"))
            .andExpect(jsonPath("$.total").value("400.00"));
        mvc.perform(get("/api/quotes").with(user("primary-test").roles("ADMIN")).session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void usesTheEditedCatalogPriceAfterTheAiDraftWasInterpreted() throws Exception {
        var draft = sendMessage(startConversation(), CATALOG_MESSAGE);
        long variantId = draft.items().getFirst().variantId();
        var original = catalog.findVariants(List.of(variantId)).get(variantId);
        var changedPrice = new VariantPriceUpdate(variantId, new BigDecimal("32.50"), original.wholesalePrice());

        try {
            mvc.perform(put("/api/catalog/prices").with(user("primary-test").roles("ADMIN"))
                    .with(csrf()).session(session).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new PricesUpdate(List.of(changedPrice)))))
                .andExpect(status().isNoContent());
            previewDraft(draft)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(15))
                .andExpect(jsonPath("$.items[0].unitPrice").value("32.50"))
                .andExpect(jsonPath("$.items[0].subtotal").value("162.50"))
                .andExpect(jsonPath("$.total").value("432.50"));
        } finally {
            catalog.updatePrices(new PricesUpdate(List.of(
                new VariantPriceUpdate(variantId, original.retailPrice(), original.wholesalePrice()))));
        }
        verify(provider, never()).interpret(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void importsTenRowsInPastedOrderWithTheTotalAfterAMarkdownDivider(boolean lineContinuations) throws Exception {
        when(provider.configured()).thenReturn(true);
        var rows = """
            • Jig Peixinho | 12g | Cor 01 | Qtd: 5
            • Jig Peixinho | 12g | Cor 04 | Qtd: 5
            • Jig Peixinho | 12g | Cor 06 | Qtd: 5
            • Jig Peixinho | 12g | Cor 05 | Qtd: 5
            • Urunaja | 10g | Cor 01 | Qtd: 5
            • Urunaja | 10g | Cor 02 | Qtd: 5
            • Urunaja | 10g | Cor 06 | Qtd: 5
            • Urunaja | 10g | Cor 08 | Qtd: 5
            • Urunaja | 10g | Cor 07 | Qtd: 5
            • Urunaja | 10g | Cor 05 | Qtd: 5
            """.stripTrailing();
        if (lineContinuations) {
            rows = rows.replace("\n", "\\\n");
        }

        var draft = sendMessage(startConversation(), rows + "\n\n---\n\n*Total: 50 unidades*");

        assertThat(draft.needsClarification()).isFalse();
        assertThat(draft.questions()).isEmpty();
        assertThat(draft.items()).extracting(ReviewItem::model, ReviewItem::weight, ReviewItem::color, ReviewItem::quantity)
            .containsExactly(tuple("Jig Peixinho", 12, "01", 5), tuple("Jig Peixinho", 12, "04", 5),
                tuple("Jig Peixinho", 12, "06", 5), tuple("Jig Peixinho", 12, "05", 5),
                tuple("Urunaja", 10, "01", 5), tuple("Urunaja", 10, "02", 5), tuple("Urunaja", 10, "06", 5),
                tuple("Urunaja", 10, "08", 5), tuple("Urunaja", 10, "07", 5), tuple("Urunaja", 10, "05", 5));
        assertThat(draft.items().stream().mapToInt(ReviewItem::quantity).sum()).isEqualTo(50);
        previewDraft(draft)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.priceTier").value("LOJISTA"))
            .andExpect(jsonPath("$.items[0].unitPrice").value("16.00"))
            .andExpect(jsonPath("$.items[9].unitPrice").value("17.00"))
            .andExpect(jsonPath("$.total").value("830.00"));
        verifyNoInteractions(provider);
    }

    @Test
    void requiresClarificationWhenThePublishedTotalDisagreesWithCompleteRows() throws Exception {
        var draft = sendMessage(startConversation(), CATALOG_MESSAGE.replace("15 unidades", "19 unidades"));

        assertThat(draft.needsClarification()).isTrue();
        assertThat(draft.items()).hasSize(3);
        assertThat(String.join(" ", draft.questions())).contains("15", "19");
        verify(provider, never()).interpret(any());
    }

    @Test
    void importsTheFullOfficialCatalogWithoutTruncatingLongPastes() throws Exception {
        var rows = catalog.list().stream()
            .flatMap(model -> model.variants().stream()
                .map(variant -> "• %s | %dg | Cor %s | Qtd: 1".formatted(model.name(), variant.weight(), variant.color())))
            .toList();
        String message = "🎣 *Pedido Urutau Jigs*\n─────────────────────\n" + String.join("\n", rows)
            + "\n─────────────────────\n*Total: " + rows.size() + " unidades*";
        assertThat(message.length()).isGreaterThan(3000);

        var draft = sendMessage(startConversation(), message);

        assertThat(draft.needsClarification()).isFalse();
        assertThat(draft.items()).hasSize(124);
        assertThat(draft.items().stream().mapToInt(ReviewItem::quantity).sum()).isEqualTo(124);
        verify(provider, never()).interpret(any());
    }

    @Test
    void reportsInvalidCatalogCombinationsAndQuantitiesWithoutGuessingReplacements() throws Exception {
        var draft = sendMessage(startConversation(), """
            • Jig Peixinho | 12g | Cor 05 | Qtd: 5
            • Urunaja | 11g | Cor 01 | Qtd: 5
            • Urunaja | 10g | Cor 99 | Qtd: 5
            • Urunaja | 10g | Cor 02 | Qtd: 0
            """);

        assertThat(draft.needsClarification()).isTrue();
        assertThat(draft.items()).extracting(ReviewItem::model, ReviewItem::quantity)
            .containsExactly(tuple("Jig Peixinho", 5));
        assertThat(draft.questions()).hasSizeGreaterThanOrEqualTo(3);
        verify(provider, never()).interpret(any());
    }

    @Test
    void keepsAnUnreadableLineVisibleAndProtectsTheConfirmedQuantity() throws Exception {
        when(provider.configured()).thenReturn(true);
        when(provider.interpret(any())).thenReturn(new Result(
            List.of(new ProposedItem("Jig Peixinho", 12, "05", 8)),
            List.of("Qual a quantidade do Jig Peixinho?"), true));
        var draft = sendMessage(startConversation(), """
            • Jig Peixinho | 12g | Cor 05 | Qtd: 5
            • Urunaja | 10g | Cor 02 | Qtd:
            """);

        assertThat(draft.needsClarification()).isTrue();
        assertThat(draft.items()).extracting(ReviewItem::model, ReviewItem::quantity)
            .containsExactly(tuple("Jig Peixinho", 5));
        assertThat(draft.questions()).anySatisfy(question -> assertThat(question).containsIgnoringCase("linha 2"));
        assertThat(draft.questions()).noneSatisfy(question -> assertThat(question).containsIgnoringCase("Jig Peixinho"));
        verify(provider).interpret(any());
    }

    @Test
    void givesGeminiTheLocallyImportedDraftForAFreeTextFollowUp() throws Exception {
        var conversation = startConversation();
        sendMessage(conversation, CATALOG_MESSAGE);
        when(provider.configured()).thenReturn(true);
        when(provider.interpret(any())).thenReturn(new Result(List.of(
            new ProposedItem("Jig Peixinho", 12, "05", 5),
            new ProposedItem("Urunaja", 10, "01", 5),
            new ProposedItem("Urunaja", 10, "02", 8)
        ), List.of(), false));

        var draft = sendMessage(conversation, "Altere a cor 02 da Urunaja para 8 unidades.");

        var input = ArgumentCaptor.forClass(Input.class);
        verify(provider).interpret(input.capture());
        assertThat(input.getValue().history()).hasSize(1);
        assertThat(input.getValue().draft().items()).containsExactly(
            new ProposedItem("Jig Peixinho", 12, "05", 5),
            new ProposedItem("Urunaja", 10, "01", 5),
            new ProposedItem("Urunaja", 10, "02", 5));
        assertThat(draft.needsClarification()).isFalse();
        assertThat(draft.items()).extracting(ReviewItem::quantity).containsExactly(5, 5, 8);
    }

    private UUID startConversation() throws Exception {
        var response = mvc.perform(post("/api/ai/conversations")
                .with(user("primary-test").roles("ADMIN")).with(csrf()).session(session))
            .andExpect(status().isCreated()).andReturn().getResponse();
        return UUID.fromString(json.readTree(response.getContentAsByteArray()).path("id").asString());
    }

    private ConversationView sendMessage(UUID conversation, String message) throws Exception {
        var response = mvc.perform(post("/api/ai/conversations/{id}/messages", conversation)
                .with(user("primary-test").roles("ADMIN")).with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("message", message))))
            .andExpect(status().isOk()).andReturn().getResponse();
        return json.readValue(response.getContentAsByteArray(), ConversationView.class);
    }

    private ResultActions previewDraft(ConversationView draft) throws Exception {
        var items = draft.items().stream().map(item -> new ItemInput(item.variantId(), item.quantity())).toList();
        return mvc.perform(post("/api/quotes/preview")
                .with(user("primary-test").roles("ADMIN")).with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("items", items))));
    }
}

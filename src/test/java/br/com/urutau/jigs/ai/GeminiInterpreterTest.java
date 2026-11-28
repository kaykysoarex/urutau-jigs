package br.com.urutau.jigs.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import br.com.urutau.jigs.ai.AiInterpreter.Input;
import br.com.urutau.jigs.ai.AiInterpreter.Model;
import br.com.urutau.jigs.ai.AiInterpreter.ProposedItem;
import br.com.urutau.jigs.ai.AiInterpreter.Result;
import br.com.urutau.jigs.ai.AiInterpreter.Turn;
import br.com.urutau.jigs.ai.AiInterpreter.WeightOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class GeminiInterpreterTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = mock(HttpClient.class);
    private final GeminiInterpreter interpreter = new GeminiInterpreter(() -> http, json, "example-api-key", "");
    private final Input input = new Input(
        List.of(new Model("Spinner", List.of(new WeightOptions(12, List.of("01", "03", "04", "06", "08"))))),
        List.of(new Turn("5 Spinner de 12g", List.of("Qual é a cor?"))),
        new Result(List.of(), List.of("Qual é a cor?"), true),
        "Cor 03"
    );

    @Test
    void sendsStructuredRequestToDefaultModelAndReadsFinalCandidateText() throws Exception {
        respond(200, completed("STOP", List.of(
            Map.of("thought", true, "text", "Raciocínio do modelo."),
            Map.of("text", "{\"items\":[{\"model\":\"Spinner\",\"weight\":12,\"color\":\"03\",\"quantity\":5}],"),
            Map.of("text", "\"questions\":[],\"needsClarification\":false}")
        )));

        var result = interpreter.interpret(input);
        assertThat(result.items()).containsExactly(new ProposedItem("Spinner", 12, "03", 5));
        assertThat(result.questions()).isEmpty();
        assertThat(result.needsClarification()).isFalse();

        var captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captured.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        var request = captured.getValue();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString()).isEqualTo(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent");
        assertThat(request.headers().firstValue("x-goog-api-key")).contains("example-api-key");
        assertThat(request.timeout()).contains(Duration.ofSeconds(35));
        String body = requestBody(request);
        var payload = json.readTree(body);
        assertThat(payload.path("generationConfig").path("responseMimeType").stringValue()).isEqualTo("application/json");
        var schema = payload.path("generationConfig").path("responseJsonSchema");
        assertThat(schema.path("additionalProperties").booleanValue()).isFalse();
        assertThat(schema.path("properties").path("items").path("items")
            .path("properties").path("quantity").path("type").stringValue()).isEqualTo("integer");
        assertThat(payload.path("systemInstruction").path("parts").path(0).path("text").stringValue())
            .contains("URUTAU JIGS", "catálogo");
        var content = payload.path("contents").path(0);
        assertThat(content.path("role").stringValue()).isEqualTo("user");
        var userInput = json.readTree(content.path("parts").path(0).path("text").stringValue());
        assertThat(userInput.path("message").stringValue()).isEqualTo("Cor 03");
        assertThat(userInput.path("history").path(0).path("message").stringValue()).isEqualTo("5 Spinner de 12g");
        assertThat(userInput.path("draft").path("needsClarification").booleanValue()).isTrue();
        assertThat(userInput.path("catalog").path(0).path("weights").path(0).path("colors").path(4).stringValue())
            .isEqualTo("08");
        assertThat(payload.has("tools")).isFalse();
        assertThat(body).doesNotContain("example-api-key", "retailPrice", "wholesalePrice");
    }

    @ParameterizedTest
    @CsvSource({"429, 429, limite", "403, 503, permissões", "404, 503, GEMINI_MODEL", "503, 503, gerador manual"})
    void reportsProviderErrorsWithoutReturningUpstreamMessages(int upstream, int appStatus, String context) throws Exception {
        respond(upstream, "upstream-sensitive-detail");
        assertThatThrownBy(() -> interpreter.interpret(input)).isInstanceOfSatisfying(ResponseStatusException.class, error -> {
            assertThat(error.getStatusCode().value()).isEqualTo(appStatus);
            assertThat(error.getReason()).contains(context).doesNotContain("upstream-sensitive-detail");
        });
        verify(http).send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MAX_TOKENS", "SAFETY"})
    void rejectsTruncatedAndBlockedCandidateEvenWhenTheCandidateContainsValidJson(String finishReason) throws Exception {
        respond(200, completed(finishReason,
            List.of(Map.of("text", "{\"items\":[],\"questions\":[],\"needsClarification\":false}"))));
        assertThatThrownBy(() -> interpreter.interpret(input)).isInstanceOfSatisfying(ResponseStatusException.class, error -> {
            assertThat(error.getStatusCode().value()).isEqualTo(503);
            assertThat(error.getReason()).contains("gerador manual");
        });
    }

    @Test
    void rejectsInvalidResultAndHandlesNetworkErrors() throws Exception {
        respond(200, completed("STOP", List.of(Map.of("text", "{\"items\":[],\"questions\":[]}"))));
        assertThatThrownBy(() -> interpreter.interpret(input)).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("503", "gerador manual");

        when(http.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenThrow(new IOException("private detail"));
        assertThatThrownBy(() -> interpreter.interpret(input)).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("503", "gerador manual").hasMessageNotContaining("private detail");
    }

    @Test
    void acceptsOnlyTheRegisteredAdministrativeIntentWithoutParameters() throws Exception {
        respond(200, completed("STOP", List.of(Map.of("text", "{\"tool\":\"CUSTOMERS\"}"))));
        assertThat(interpreter.classifyAdministrativeIntent("recuperar relacao compradores cadastrados", "OVERVIEW"))
            .isEqualTo(AssistantTools.Tool.CUSTOMERS);

        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertThat(requestBody(request.getValue())).contains("CUSTOMERS", "additionalProperties", "recuperar relacao compradores")
            .doesNotContain("example-api-key");
        respond(200, completed("STOP", List.of(Map.of("text", "{\"tool\":\"SQL\"}"))));
        assertThatThrownBy(() -> interpreter.classifyAdministrativeIntent("relacao clientes", "CUSTOMERS"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
        respond(200, completed("STOP", List.of(Map.of("text", "{\"tool\":\"CUSTOMER_CREATE\",\"name\":\"alterado\"}"))));
        assertThatThrownBy(() -> interpreter.classifyAdministrativeIntent("relacao clientes", "CUSTOMERS"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
    }

    @Test
    void refusesMissingCredentialsAndInvalidModelPathWithoutNetworkCall() {
        var missingKey = new GeminiInterpreter(() -> http, json, "  ", "gemini-3.5-flash-lite");
        var unsafeModel = new GeminiInterpreter(() -> http, json, "example-api-key", "gemini-3.5/../../other");
        assertThat(missingKey.configured()).isFalse();
        assertThat(unsafeModel.configured()).isFalse();
        assertThatThrownBy(() -> unsafeModel.interpret(input)).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("GEMINI_MODEL");
        verifyNoInteractions(http);
    }

    private String completed(String finishReason, List<Map<String, Object>> parts) {
        return json.writeValueAsString(Map.of("candidates", List.of(
            Map.of("finishReason", finishReason, "content", Map.of("parts", parts))
        )));
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, String body) throws IOException, InterruptedException {
        var response = (HttpResponse<String>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
    }

    private static String requestBody(HttpRequest request) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var complete = new CompletableFuture<String>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            @Override public void onError(Throwable error) {
                complete.completeExceptionally(error);
            }
            @Override public void onComplete() {
                complete.complete(bytes.toString(StandardCharsets.UTF_8));
            }
        });
        return complete.get(5, TimeUnit.SECONDS);
    }
}

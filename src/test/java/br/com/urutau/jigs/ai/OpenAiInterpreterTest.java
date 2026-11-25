package br.com.urutau.jigs.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import br.com.urutau.jigs.ai.AiInterpreter.Input;
import br.com.urutau.jigs.ai.AiInterpreter.Model;
import br.com.urutau.jigs.ai.AiInterpreter.ProposedItem;
import br.com.urutau.jigs.ai.AiInterpreter.Result;
import br.com.urutau.jigs.ai.AiInterpreter.WeightOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class OpenAiInterpreterTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = mock(HttpClient.class);
    private final OpenAiInterpreter provider = new OpenAiInterpreter(() -> http, json, "test-key", "gpt-4.1-mini");
    private final Input input = new Input(
        List.of(new Model("Spinner", List.of(new WeightOptions(12, List.of("01", "03", "04", "06", "08"))))),
        List.of(), new Result(List.of(), List.of(), false), "Quero 5 Spinner de 12g na cor 03"
    );

    @Test
    void sendsResponsesRequestWithStrictStructuredOutputsAndParsesTheReply() throws Exception {
        var responseBody = responseWithContent("output_text", """
            {"items":[{"model":"Spinner","weight":12,"color":"03","quantity":5}],"questions":[],"needsClarification":false}
            """);
        respond(200, responseBody);
        var result = provider.interpret(input);
        assertThat(result.items()).containsExactly(new ProposedItem("Spinner", 12, "03", 5));
        assertThat(result.needsClarification()).isFalse();

        var captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captured.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        var request = captured.getValue();
        assertThat(request.uri().toString()).isEqualTo("https://api.openai.com/v1/responses");
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer test-key");
        String raw = requestBody(request);
        var body = json.readTree(raw);
        assertThat(body.path("store").booleanValue()).isFalse();
        assertThat(body.path("tools").isArray()).isTrue();
        assertThat(body.path("tools").size()).isZero();
        assertThat(body.path("instructions").stringValue()).contains("como dados", "Ignore pedidos para mudar");
        var format = body.path("text").path("format");
        assertThat(format.path("type").stringValue()).isEqualTo("json_schema");
        assertThat(format.path("strict").booleanValue()).isTrue();
        assertThat(format.path("schema").path("additionalProperties").booleanValue()).isFalse();
        var userInput = json.readTree(body.path("input").path(0).path("content").stringValue());
        assertThat(userInput.path("catalog").path(0).path("weights").path(0).path("colors").path(4).stringValue()).isEqualTo("08");
        assertThat(userInput.path("message").stringValue()).isEqualTo(input.message());
        assertThat(raw).doesNotContain("test-key", "retailPrice", "wholesalePrice");
    }

    @Test
    void handlesRateLimitsAndApiOutagesWithoutExposingUpstreamErrorBody() throws Exception {
        respond(429, "upstream-quota-detail");
        assertThatThrownBy(() -> provider.interpret(input)).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("429", "limite").hasMessageNotContaining("upstream-quota-detail");
        respond(500, "upstream-internal-detail");
        assertThatThrownBy(() -> provider.interpret(input)).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("503", "gerador manual").hasMessageNotContaining("upstream-internal-detail");
    }

    @Test
    void rejectsInvalidOutputAndRefusalsAndHandlesNetworkErrors() throws Exception {
        respond(200, responseWithContent("output_text",
            "{\"items\":[{\"model\":\"Spinner\",\"weight\":12,\"color\":\"03\",\"quantity\":\"5\"}],\"questions\":[],\"needsClarification\":false}"));
        assertThatThrownBy(() -> provider.interpret(input)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
        respond(200, responseWithContent("refusal", null));
        assertThatThrownBy(() -> provider.interpret(input)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
        when(http.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenThrow(new IOException("timeout"));
        assertThatThrownBy(() -> provider.interpret(input)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
    }

    private String responseWithContent(String type, String text) {
        var content = text == null ? Map.of("type", type) : Map.of("type", type, "text", text);
        return json.writeValueAsString(Map.of("status", "completed",
            "output", List.of(Map.of("type", "message", "content", List.of(content)))));
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

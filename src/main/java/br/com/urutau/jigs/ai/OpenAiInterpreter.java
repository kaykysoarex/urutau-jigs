package br.com.urutau.jigs.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
class OpenAiInterpreter implements AiInterpreter {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAiInterpreter.class);
    private static final URI RESPONSES_ENDPOINT = URI.create("https://api.openai.com/v1/responses");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(35);
    private static final String UNAVAILABLE = "A URUTAU AI está indisponível no momento. Tente de novo ou use o gerador manual.";
    private static final String DEFAULT_MODEL = "gpt-4.1-mini";

    private final Supplier<HttpClient> http;
    private final JsonMapper json;
    private final String apiKey;
    private final String model;

    @Autowired
    OpenAiInterpreter(JsonMapper json,
                      @Value("${urutau.ai.openai.api-key:}") String apiKey,
                      @Value("${urutau.ai.openai.model:}") String model) {
        this(AiHttpClient::get, json, apiKey, model);
    }

    OpenAiInterpreter(Supplier<HttpClient> http, JsonMapper json, String apiKey, String model) {
        this.http = http;
        this.json = json;
        this.apiKey = apiKey.strip();
        this.model = model.isBlank() ? DEFAULT_MODEL : model.strip();
    }

    @Override
    public boolean configured() {
        return !apiKey.isEmpty();
    }

    @Override
    public Result interpret(Input input) {
        try {
            var request = HttpRequest.newBuilder(RESPONSES_ENDPOINT)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson(input)))
                .build();
            var response = http.get().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "A OpenAI atingiu o limite de uso. Aguarde e tente de novo ou use o gerador manual.");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warn("OpenAI Responses API returned HTTP {}", response.statusCode());
                throw unavailable();
            }
            return parseResponse(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | UncheckedIOException | JacksonException | IllegalArgumentException ex) {
            LOG.warn("OpenAI Responses API request failed ({})", ex.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private String requestJson(Input input) {
        var format = Map.of("type", "json_schema", "name", "urutau_quote_draft", "strict", true, "schema", AiOrderProtocol.SCHEMA);
        var providerInput = Map.of("role", "user", "content", json.writeValueAsString(input));
        return json.writeValueAsString(Map.of(
            "model", model,
            "instructions", AiOrderProtocol.INSTRUCTIONS,
            "input", List.of(providerInput),
            "max_output_tokens", 12_000,
            "store", false,
            "tools", List.of(),
            "text", Map.of("format", format)
        ));
    }

    private Result parseResponse(String body) {
        var root = json.readTree(body);
        if (root == null || !root.isObject() || !"completed".equals(root.path("status").stringValue())
            || !root.path("output").isArray()) {
            throw unavailable();
        }
        String structuredOutput = null;
        for (var output : root.path("output")) {
            if (!"message".equals(output.path("type").stringValue())) {
                continue;
            }
            for (var content : output.path("content")) {
                String type = content.path("type").stringValue();
                if ("refusal".equals(type) || ("output_text".equals(type) && structuredOutput != null)) {
                    throw unavailable();
                }
                if ("output_text".equals(type)) {
                    structuredOutput = content.path("text").stringValue();
                }
            }
        }
        if (structuredOutput == null || structuredOutput.isBlank()) {
            throw unavailable();
        }
        return AiOrderProtocol.parseResult(json.readTree(structuredOutput));
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
    }
}

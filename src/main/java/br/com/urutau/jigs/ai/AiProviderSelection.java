package br.com.urutau.jigs.ai;

import java.util.Locale;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Primary
class AiProviderSelection implements AiInterpreter {
    private final AiInterpreter selected;

    AiProviderSelection(@Value("${urutau.ai.provider:gemini}") String provider,
                        GeminiInterpreter gemini, OpenAiInterpreter openAi) {
        selected = switch (provider.strip().toLowerCase(Locale.ROOT)) {
            case "", "gemini" -> gemini;
            case "openai" -> openAi;
            default -> {
                LoggerFactory.getLogger(AiProviderSelection.class).warn("AI_PROVIDER inválido. Use gemini ou openai.");
                yield null;
            }
        };
    }

    @Override
    public boolean configured() {
        return selected != null && selected.configured();
    }

    @Override
    public Result interpret(Input input) {
        if (!configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Configure o provedor da URUTAU AI. O gerador manual está disponível.");
        }
        return selected.interpret(input);
    }
}

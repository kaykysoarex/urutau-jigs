package br.com.urutau.jigs.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.urutau.jigs.ai.AiInterpreter.Input;
import br.com.urutau.jigs.ai.AiInterpreter.Result;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AiProviderSelectionTest {
    private final GeminiInterpreter gemini = mock(GeminiInterpreter.class);
    private final OpenAiInterpreter openAi = mock(OpenAiInterpreter.class);
    private final Result result = new Result(List.of(), List.of(), false);
    private final Input input = new Input(List.of(), List.of(), result, "3 Spinner 12g cor 03");

    @Test
    void usesGeminiForTheDefaultAndHasNoFallbackOnRateLimit() {
        when(gemini.configured()).thenReturn(true);
        when(gemini.interpret(input)).thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS));

        var provider = new AiProviderSelection("", gemini, openAi);

        assertThat(provider.configured()).isTrue();
        assertThatThrownBy(() -> provider.interpret(input)).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("429");
        verifyNoInteractions(openAi);
    }

    @Test
    void requiresTheGeminiKeyEvenIfAnOpenAiKeyIsPresent() {
        when(openAi.configured()).thenReturn(true);

        var provider = new AiProviderSelection("gemini", gemini, openAi);

        assertThat(provider.configured()).isFalse();
        assertThatThrownBy(() -> provider.interpret(input)).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("503");
        verifyNoInteractions(openAi);
    }

    @Test
    void usesOpenAiOnlyWhenExplicitlySelected() {
        when(openAi.configured()).thenReturn(true);
        when(openAi.interpret(input)).thenReturn(result);

        var provider = new AiProviderSelection("OpenAI", gemini, openAi);

        assertThat(provider.interpret(input)).isEqualTo(result);
        verifyNoInteractions(gemini);
    }

    @Test
    void invalidProviderConfigurationLeavesTheServiceUnavailableWithoutContactingEitherProvider() {
        var provider = new AiProviderSelection("outro", gemini, openAi);

        assertThat(provider.configured()).isFalse();
        assertThatThrownBy(() -> provider.interpret(input)).isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("503");
        verifyNoInteractions(gemini, openAi);
    }
}

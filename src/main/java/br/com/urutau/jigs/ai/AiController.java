package br.com.urutau.jigs.ai;

import java.util.UUID;

import br.com.urutau.jigs.ai.AiDtos.Configuration;
import br.com.urutau.jigs.ai.AiDtos.ConversationView;
import br.com.urutau.jigs.ai.AiDtos.MessageRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
class AiController {
    private final AiConversationService conversations;

    AiController(AiConversationService conversations) {
        this.conversations = conversations;
    }

    @GetMapping
    Configuration configuration() {
        return new Configuration(conversations.configured());
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    ConversationView start(HttpSession session) {
        return conversations.start(session);
    }

    @PostMapping("/conversations/{id}/messages")
    ConversationView message(HttpSession session, @PathVariable UUID id, @Valid @RequestBody MessageRequest request) {
        return conversations.message(session, id, request.message());
    }
}


package br.com.urutau.jigs.ai;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assistant")
class AdministrativeAssistantController {
    private static final String ATTRIBUTE = AdministrativeAssistantController.class.getName();
    private static final int MAX_MESSAGES = 40;
    private final AdministrativeAssistant assistant;

    AdministrativeAssistantController(AdministrativeAssistant assistant) {
        this.assistant = assistant;
    }

    @GetMapping
    ResponseEntity<List<Message>> history(HttpSession session, Principal principal) {
        var conversation = conversation(session, principal.getName());
        synchronized (conversation) {
            return response(List.copyOf(conversation.messages));
        }
    }

    @PostMapping("/messages")
    ResponseEntity<List<Message>> send(@Valid @RequestBody Question question, HttpSession session, Principal principal) {
        var conversation = conversation(session, principal.getName());
        synchronized (conversation) {
            String answer = assistant.answer(question.section(), question.message());
            conversation.messages.add(new Message("admin", question.message().strip()));
            conversation.messages.add(new Message("assistant", answer));
            if (conversation.messages.size() > MAX_MESSAGES) {
                conversation.messages.subList(0, conversation.messages.size() - MAX_MESSAGES).clear();
            }
            return response(List.copyOf(conversation.messages));
        }
    }

    @DeleteMapping
    ResponseEntity<List<Message>> clear(HttpSession session, Principal principal) {
        var conversation = conversation(session, principal.getName());
        synchronized (conversation) {
            conversation.messages.clear();
            return response(List.of());
        }
    }

    private static Conversation conversation(HttpSession session, String owner) {
        synchronized (session) {
            if (session.getAttribute(ATTRIBUTE) instanceof Conversation saved && saved.owner.equals(owner)) return saved;
            var fresh = new Conversation(owner);
            session.setAttribute(ATTRIBUTE, fresh);
            return fresh;
        }
    }

    private static ResponseEntity<List<Message>> response(List<Message> messages) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(messages);
    }

    record Question(@NotNull AdministrativeAssistant.Section section, @NotBlank @Size(max = 1000) String message) {}
    record Message(String role, String text) {}

    private static final class Conversation {
        private final String owner;
        private final List<Message> messages = new ArrayList<>();
        private Conversation(String owner) { this.owner = owner; }
    }
}

package br.com.urutau.jigs.ai;

import static br.com.urutau.jigs.ai.AssistantTools.*;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/assistant")
class AdministrativeAssistantController {
    private static final String ATTRIBUTE = AdministrativeAssistantController.class.getName();
    private static final int MAX_MESSAGES = 40;
    private static final Duration VALIDITY = Duration.ofMinutes(5);
    private final AssistantCoordinator assistant;
    private final AssistantAudit audit;
    private final Clock clock;

    AdministrativeAssistantController(AssistantCoordinator assistant, AssistantAudit audit, Clock clock) {
        this.assistant = assistant;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping
    ResponseEntity<List<Message>> history(HttpSession session, Authentication authentication) {
        var conversation = conversation(session, new Actor(authentication).name());
        synchronized (conversation) {
            expire(conversation);
            return response(List.copyOf(conversation.messages));
        }
    }

    @PostMapping("/messages")
    ResponseEntity<List<Message>> send(@Valid @RequestBody Question question, HttpSession session, Authentication authentication) {
        var actor = new Actor(authentication);
        var conversation = conversation(session, actor.name());
        synchronized (conversation) {
            expire(conversation);
            Instant now = clock.instant();
            if (question.equals(conversation.lastQuestion) && now.isBefore(conversation.lastAt.plusSeconds(2))) {
                return response(List.copyOf(conversation.messages));
            }
            conversation.requests.removeIf(at -> at.isBefore(now.minusSeconds(60)));
            if (conversation.requests.size() >= 18) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Aguarde um minuto antes de enviar outra mensagem.");
            conversation.requests.add(now);
            var reply = assistant.answer(actor, question.section(), question.message(), question.draftId(), conversation.context);
            conversation.messages.add(new Message("admin", question.message().strip(), List.of(), null));
            append(conversation, reply, now);
            conversation.lastQuestion = question;
            conversation.lastAt = now;
            return response(List.copyOf(conversation.messages));
        }
    }

    @PostMapping("/proposals/{id}/decision")
    ResponseEntity<List<Message>> decide(@PathVariable UUID id, @Valid @RequestBody Decision decision,
                                         HttpSession session, Authentication authentication) {
        var actor = new Actor(authentication);
        var conversation = conversation(session, actor.name());
        synchronized (conversation) {
            expire(conversation);
            var pending = conversation.pending;
            if (pending == null || !pending.id().equals(id)) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A proposta expirou ou já foi encerrada. Solicite uma nova revisão.");
            conversation.pending = null;
            if (!decision.confirm()) {
                settle(conversation, pending, "Cancelada");
                append(conversation, new Reply("Proposta cancelada."), clock.instant());
            } else {
                Reply result;
                try {
                    result = audit.execute(id, actor, pending.proposal());
                    settle(conversation, pending, "Confirmada");
                    if (pending.proposal().tool() == Tool.ISSUE || pending.proposal().tool() == Tool.DRAFT_DISCARD) {
                        conversation.context.draft = null;
                    }
                } catch (RuntimeException failure) {
                    audit.failed(id, actor, pending.proposal());
                    settle(conversation, pending, "Falhou");
                    String reason = failure instanceof ResponseStatusException status ? status.getReason() : null;
                    result = new Reply(reason == null ? "A operação não foi concluída. Revise os dados antes de tentar novamente." : reason);
                }
                append(conversation, result, clock.instant());
            }
            conversation.lastQuestion = null;
            return response(List.copyOf(conversation.messages));
        }
    }

    @DeleteMapping
    ResponseEntity<List<Message>> clear(HttpSession session, Authentication authentication) {
        String owner = new Actor(authentication).name();
        var current = conversation(session, owner);
        synchronized (current) {
            current.messages.clear();
            current.context = new AssistantCoordinator.Context();
            current.pending = null;
            current.lastQuestion = null;
        }
        return response(List.of());
    }

    private void expire(Conversation conversation) {
        var pending = conversation.pending;
        if (pending != null && !clock.instant().isBefore(pending.until())) {
            settle(conversation, pending, "Expirada");
            conversation.pending = null;
        }
    }

    private static void append(Conversation conversation, Reply reply, Instant now) {
        ProposalView view = null;
        if (reply.proposal() != null) {
            var previous = conversation.pending;
            if (previous != null) settle(conversation, previous, "Substituída");
            var pending = new Pending(UUID.randomUUID(), now.plus(VALIDITY), reply.proposal());
            conversation.pending = pending;
            view = new ProposalView(pending.id(), pending.until(), "Pendente", pending.proposal().card());
        }
        if (reply.customer() != null) conversation.context.customer = reply.customer();
        if (reply.quote() != null) conversation.context.quote = reply.quote();
        if (reply.draft() != null) conversation.context.draft = reply.draft();
        conversation.messages.add(new Message("assistant", reply.text(), reply.cards(), view));
        if (conversation.messages.size() > MAX_MESSAGES) {
            conversation.messages.subList(0, conversation.messages.size() - MAX_MESSAGES).clear();
        }
    }

    private static void settle(Conversation conversation, Pending pending, String state) {
        for (int i = conversation.messages.size() - 1; i >= 0; i--) {
            var message = conversation.messages.get(i);
            if (message.proposal() != null && message.proposal().id().equals(pending.id())) {
                conversation.messages.set(i, new Message(message.role(), message.text(), message.cards(),
                    new ProposalView(pending.id(), pending.until(), state, pending.proposal().card())));
                return;
            }
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

    record Question(@NotNull AdministrativeAssistant.Section section, @NotBlank @Size(max = 1000) String message, UUID draftId) {}
    record Decision(@NotNull Boolean confirm) {}
    record ProposalView(UUID id, Instant expiresAt, String status, Card card) {}
    record Message(String role, String text, List<Card> cards, ProposalView proposal) {}
    private record Pending(UUID id, Instant until, Proposal proposal) {}

    private static final class Conversation {
        private final String owner;
        private final List<Message> messages = new ArrayList<>();
        private final List<Instant> requests = new ArrayList<>();
        private AssistantCoordinator.Context context = new AssistantCoordinator.Context();
        private Pending pending;
        private Question lastQuestion;
        private Instant lastAt;
        private Conversation(String owner) { this.owner = owner; }
    }
}

package br.com.urutau.jigs.ai;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

public final class AssistantTools {
    private AssistantTools() {}

    public enum Tool {
        CUSTOMERS, CUSTOMER_HISTORY, ORDERS, QUOTES, RECORD, DRAFTS, DRAFT, CATALOG, PRICES, SETTINGS, INDICATORS,
        PRODUCTION_QUEUE, PRODUCTION_FIRST, PRODUCTION_DETAILS, PRODUCTION_LINK,
        CUSTOMER_CREATE, CUSTOMER_EDIT, CUSTOMER_ACTIVATE, CUSTOMER_LINK,
        DRAFT_CREATE, DRAFT_DUPLICATE, DRAFT_UPDATE, DRAFT_DISCARD, ISSUE,
        ORDER_CONFIRM, ORDER_CANCEL, ORDER_DELIVER, ORDER_TERMS,
        PRODUCT_CREATE, PRODUCT_UPDATE, PRICE_UPDATE, SETTINGS_UPDATE;

        public boolean write() { return ordinal() >= CUSTOMER_CREATE.ordinal(); }
    }

    public record Action(String kind, String target, String label) {}
    public record Card(String title, List<String> details, List<Action> actions) {
        public Card(String title, List<String> details) { this(title, details, List.of()); }
    }
    public record Reply(String text, List<Card> cards, Proposal proposal, Long customer, Long quote, UUID draft) {
        public Reply(String text, List<Card> cards) { this(text, cards, null, null, null, null); }
        public Reply(String text) { this(text, List.of()); }
        public Reply context(Long customer, Long quote, UUID draft) {
            return new Reply(text, cards, proposal, customer, quote, draft);
        }
    }
    public record Proposal(Tool tool, String entity, String target, Card card,
                           String auditBefore, String auditAfter, Work work) {
        public Reply preview() { return new Reply("Confira a proposta antes de confirmar.", List.of(), this, null, null, null); }
    }
    @FunctionalInterface public interface Work { Reply execute(UUID requestId); }

    public record Actor(Authentication authentication) {
        public String name() {
            if (authentication == null || !authentication.isAuthenticated() || authentication.getAuthorities().stream()
                    .noneMatch(role -> "ROLE_ADMIN".equals(role.getAuthority()))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A ferramenta exige acesso administrativo.");
            }
            return authentication.getName();
        }
    }

    public static void unchanged(Object before, Object current) {
        if (!Objects.equals(before, current)) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "O registro mudou desde a proposta. Solicite uma nova revisão.");
    }
    public static ResponseStatusException invalid(String text) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, text);
    }
}

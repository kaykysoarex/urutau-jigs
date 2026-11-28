package br.com.urutau.jigs.ai;

import br.com.urutau.jigs.config.OperationAudit;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class AssistantAudit {
    private final JdbcClient jdbc;
    private final OperationAudit audit;

    AssistantAudit(JdbcClient jdbc, OperationAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    AssistantTools.Reply execute(UUID correlation, AssistantTools.Actor actor, AssistantTools.Proposal proposal) {
        String owner = actor.name();
        insert(correlation, owner, proposal, "SUCESSO");
        var result = proposal.work().execute(correlation);
        String actual = switch (proposal.tool()) {
            case CUSTOMER_CREATE -> result.customer() == null ? null : result.customer().toString();
            case DRAFT_CREATE, DRAFT_DUPLICATE -> result.draft() == null ? null : result.draft().toString();
            case ISSUE -> result.quote() == null ? null : result.quote().toString();
            case PRODUCT_CREATE -> result.cards().stream().flatMap(card -> card.actions().stream())
                .filter(action -> action.kind().equals("product")).map(AssistantTools.Action::target).findFirst().orElse(null);
            default -> null;
        };
        if (actual != null) jdbc.sql("update assistant_audit set entity_ref = :ref where correlation_id = :id")
            .param("ref", actual).param("id", correlation.toString()).update();
        return result;
    }

    @Transactional
    void failed(UUID correlation, AssistantTools.Actor actor, AssistantTools.Proposal proposal) {
        insert(correlation, actor.name(), proposal, "FALHA");
    }

    private void insert(UUID correlation, String owner, AssistantTools.Proposal proposal, String result) {
        audit.record(correlation, owner, "URUTAU_AI", proposal.tool().name(), proposal.entity(), proposal.target(),
            proposal.auditBefore(), proposal.auditAfter(), result);
    }
}

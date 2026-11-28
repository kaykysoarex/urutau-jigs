package br.com.urutau.jigs.config;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class OperationAudit {
    private final JdbcClient jdbc;
    private final Clock clock;

    public OperationAudit(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(UUID id, String actor, String origin, String tool, String entity, String reference,
                       String before, String after, String result) {
        jdbc.sql("""
            insert into assistant_audit (correlation_id, actor, occurred_at, origin, tool, entity_kind, entity_ref,
                before_values, after_values, result)
            values (:id, :actor, :at, :origin, :tool, :entity, :ref, :before, :after, :result)
            """).param("id", id.toString()).param("actor", actor).param("at", Timestamp.from(clock.instant()))
            .param("origin", origin).param("tool", tool).param("entity", entity).param("ref", reference)
            .param("before", before).param("after", after).param("result", result).update();
    }
}

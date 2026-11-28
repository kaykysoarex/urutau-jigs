CREATE TABLE assistant_audit (
    correlation_id VARCHAR(36) PRIMARY KEY,
    actor VARCHAR(120) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    origin VARCHAR(24) NOT NULL,
    tool VARCHAR(40) NOT NULL,
    entity_kind VARCHAR(24) NOT NULL,
    entity_ref VARCHAR(120) NOT NULL,
    before_values TEXT,
    after_values TEXT,
    result VARCHAR(16) NOT NULL
);
CREATE INDEX idx_assistant_audit_time ON assistant_audit (occurred_at);

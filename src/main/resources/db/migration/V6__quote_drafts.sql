
CREATE TABLE quote_draft (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner VARCHAR(120) NOT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    content TEXT,
    estimate_token VARCHAR(64),
    estimated_total DECIMAL(24, 2)
);

CREATE INDEX idx_quote_draft_owner_updated ON quote_draft (owner, updated_at);

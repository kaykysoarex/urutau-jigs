
ALTER TABLE commercial_quote ADD COLUMN cancelled_at DATETIME(6);
ALTER TABLE commercial_quote ADD COLUMN cancelled_by VARCHAR(80);
ALTER TABLE commercial_quote ADD COLUMN cancellation_reason VARCHAR(500);
CREATE INDEX idx_quote_commercial_status ON commercial_quote (status, created_at);

CREATE TABLE commercial_order (
    number BIGINT AUTO_INCREMENT PRIMARY KEY,
    internal_id VARCHAR(36) NOT NULL UNIQUE,
    quote_id BIGINT NOT NULL UNIQUE,
    confirmed_at DATETIME(6) NOT NULL,
    confirmed_by VARCHAR(80) NOT NULL,
    expected_on DATE,
    notes VARCHAR(1000) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT fk_commercial_order_quote FOREIGN KEY (quote_id) REFERENCES commercial_quote(id)
);
CREATE INDEX idx_order_confirmation ON commercial_order (confirmed_at, number);

CREATE TABLE commercial_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    quote_id BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL,
    actor VARCHAR(80) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    expected_on DATE,
    notes VARCHAR(1000),
    reason VARCHAR(500),
    CONSTRAINT fk_commercial_event_quote FOREIGN KEY (quote_id) REFERENCES commercial_quote(id)
);
CREATE INDEX idx_commercial_event_quote ON commercial_event (quote_id, id);


CREATE TABLE commercial_customer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    establishment VARCHAR(120),
    phone VARCHAR(40),
    normalized_phone VARCHAR(15),
    email VARCHAR(254),
    city VARCHAR(120),
    state VARCHAR(2),
    notes VARCHAR(1000),
    active BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL
);
CREATE INDEX idx_customer_phone ON commercial_customer (normalized_phone);
CREATE INDEX idx_customer_name ON commercial_customer (name, id);

CREATE TABLE customer_audit_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    actor VARCHAR(80) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_customer_audit FOREIGN KEY (customer_id) REFERENCES commercial_customer(id)
);
CREATE INDEX idx_customer_audit_customer ON customer_audit_event (customer_id, id);

CREATE TABLE customer_write_guard (id INTEGER PRIMARY KEY);
INSERT INTO customer_write_guard (id) VALUES (1);

ALTER TABLE commercial_quote ADD COLUMN customer_id BIGINT;
ALTER TABLE commercial_quote ADD CONSTRAINT fk_commercial_quote_customer
    FOREIGN KEY (customer_id) REFERENCES commercial_customer(id);
CREATE INDEX idx_quote_customer_status ON commercial_quote (customer_id, status, id);

CREATE TABLE commercial_quote (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(36) NOT NULL,
    created_by VARCHAR(80) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    issued_on DATE NOT NULL,
    client_name VARCHAR(120) NOT NULL,
    client_phone VARCHAR(40),
    client_city VARCHAR(120),
    client_state VARCHAR(2),
    total_quantity INTEGER NOT NULL,
    total DECIMAL(24, 2) NOT NULL,
    price_tier VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    CONSTRAINT uq_quote_request UNIQUE (created_by, request_id)
);

CREATE TABLE commercial_quote_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    quote_id BIGINT NOT NULL,
    variant_id BIGINT NOT NULL,
    model_name VARCHAR(120) NOT NULL,
    weight_grams INTEGER NOT NULL,
    color_code VARCHAR(16) NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price DECIMAL(24, 2) NOT NULL,
    subtotal DECIMAL(24, 2) NOT NULL,
    CONSTRAINT fk_item_quote FOREIGN KEY (quote_id) REFERENCES commercial_quote(id),
    CONSTRAINT fk_item_variant FOREIGN KEY (variant_id) REFERENCES catalog_variant(id)
);

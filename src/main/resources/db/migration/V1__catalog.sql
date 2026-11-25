CREATE TABLE catalog_product (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(120) NOT NULL,
    name_key VARCHAR(240) NOT NULL,
    active BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_catalog_product_name UNIQUE (name_key)
);

CREATE TABLE catalog_variant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    weight_grams INTEGER NOT NULL,
    color_code VARCHAR(16) NOT NULL,
    active BOOLEAN NOT NULL,
    retail_price DECIMAL(12, 2),
    wholesale_price DECIMAL(12, 2),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_catalog_variant_product FOREIGN KEY (product_id) REFERENCES catalog_product (id),
    CONSTRAINT uq_catalog_variant UNIQUE (product_id, weight_grams, color_code),
    CONSTRAINT ck_catalog_variant_weight CHECK (weight_grams > 0),
    CONSTRAINT ck_catalog_variant_retail CHECK (retail_price >= 0),
    CONSTRAINT ck_catalog_variant_wholesale CHECK (wholesale_price >= 0)
);

CREATE TABLE commercial_settings (
    id INTEGER NOT NULL,
    wholesale_threshold INTEGER NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT ck_commercial_settings_single_row CHECK (id = 1),
    CONSTRAINT ck_commercial_settings_threshold CHECK (wholesale_threshold > 0)
);

INSERT INTO commercial_settings (id, wholesale_threshold) VALUES (1, 30);

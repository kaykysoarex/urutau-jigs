ALTER TABLE commercial_order ADD COLUMN delivered_at DATETIME(6);
ALTER TABLE commercial_order ADD COLUMN delivered_by VARCHAR(80);
ALTER TABLE commercial_order ADD CONSTRAINT ck_order_delivery
    CHECK ((delivered_at IS NULL AND delivered_by IS NULL)
        OR (delivered_at IS NOT NULL AND delivered_by IS NOT NULL));

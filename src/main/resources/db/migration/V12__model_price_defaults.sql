ALTER TABLE catalog_product ADD COLUMN default_retail_price DECIMAL(12,2);
ALTER TABLE catalog_product ADD COLUMN default_wholesale_price DECIMAL(12,2);

-- Only infer a default when every existing combination agrees on that tier.
UPDATE catalog_product
SET default_retail_price = (
    SELECT CASE WHEN COUNT(*) = COUNT(v.retail_price) AND MIN(v.retail_price) = MAX(v.retail_price)
        THEN MIN(v.retail_price) END
    FROM catalog_variant v WHERE v.product_id = catalog_product.id
), default_wholesale_price = (
    SELECT CASE WHEN COUNT(*) = COUNT(v.wholesale_price) AND MIN(v.wholesale_price) = MAX(v.wholesale_price)
        THEN MIN(v.wholesale_price) END
    FROM catalog_variant v WHERE v.product_id = catalog_product.id
);

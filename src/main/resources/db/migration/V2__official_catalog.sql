-- Catálogo oficial consultado em 25/11/2026.
-- Fonte: https://catalogo-urutau.vercel.app/catalog-data.js
-- script.js valida cada gramatura contra as cores verificadas do modelo.
-- Os preços ficam nulos até o cadastro pelo administrador.
INSERT INTO catalog_product (name, name_key, active) VALUES
    ('Urunaja', 'urunaja', TRUE),
    ('Urumax G1', 'urumax g1', TRUE),
    ('Urumax G2', 'urumax g2', TRUE),
    ('Jig-Bolinha', 'jig-bolinha', TRUE),
    ('Micro-Jig', 'micro-jig', TRUE),
    ('Spinner', 'spinner', TRUE),
    ('Jig Peixinho', 'jig peixinho', TRUE),
    ('Jig Urutau', 'jig urutau', TRUE),
    ('Jig Articulado', 'jig articulado', TRUE);

-- Urunaja
INSERT INTO catalog_variant (product_id, weight_grams, color_code, active)
SELECT product.id, weights.grams, colors.code, TRUE
FROM catalog_product product
CROSS JOIN (SELECT 10 AS grams UNION ALL SELECT 12) weights
CROSS JOIN (SELECT '01' AS code UNION ALL SELECT '02' UNION ALL SELECT '03' UNION ALL SELECT '04' UNION ALL SELECT '05' UNION ALL SELECT '06' UNION ALL SELECT '07' UNION ALL SELECT '08' UNION ALL SELECT '09') colors
WHERE product.name_key = 'urunaja';

-- Urumax G1
INSERT INTO catalog_variant (product_id, weight_grams, color_code, active)
SELECT product.id, weights.grams, colors.code, TRUE
FROM catalog_product product
CROSS JOIN (SELECT 13 AS grams UNION ALL SELECT 16) weights
CROSS JOIN (SELECT '01' AS code UNION ALL SELECT '02' UNION ALL SELECT '03' UNION ALL SELECT '04' UNION ALL SELECT '05' UNION ALL SELECT '06' UNION ALL SELECT '07' UNION ALL SELECT '08' UNION ALL SELECT '09' UNION ALL SELECT '10') colors
WHERE product.name_key = 'urumax g1';

-- Urumax G2
INSERT INTO catalog_variant (product_id, weight_grams, color_code, active)
SELECT product.id, weights.grams, colors.code, TRUE
FROM catalog_product product
CROSS JOIN (SELECT 16 AS grams) weights
CROSS JOIN (SELECT '01' AS code UNION ALL SELECT '02' UNION ALL SELECT '03' UNION ALL SELECT '04' UNION ALL SELECT '05' UNION ALL SELECT '06' UNION ALL SELECT '07') colors
WHERE product.name_key = 'urumax g2';

-- Jig-Bolinha
INSERT INTO catalog_variant (product_id, weight_grams, color_code, active)
SELECT product.id, weights.grams, colors.code, TRUE
FROM catalog_product product
CROSS JOIN (SELECT 12 AS grams UNION ALL SELECT 14) weights
CROSS JOIN (SELECT '01' AS code UNION ALL SELECT '02' UNION ALL SELECT '03') colors
WHERE product.name_key = 'jig-bolinha';

-- Micro-Jig
INSERT INTO catalog_variant (product_id, weight_grams, color_code, active)
SELECT product.id, weights.grams, colors.code, TRUE
FROM catalog_product product
CROSS JOIN (SELECT 8 AS grams UNION ALL SELECT 12 UNION ALL SELECT 14) weights
CROSS JOIN (SELECT '01' AS code UNION ALL SELECT '02' UNION ALL SELECT '03' UNION ALL SELECT '04' UNION ALL SELECT '05' UNION ALL SELECT '06') colors
WHERE product.name_key = 'micro-jig';

-- Spinner
INSERT INTO catalog_variant (product_id, weight_grams, color_code, active)
SELECT product.id, weights.grams, colors.code, TRUE
FROM catalog_product product
CROSS JOIN (SELECT 12 AS grams UNION ALL SELECT 14 UNION ALL SELECT 16) weights
CROSS JOIN (SELECT '01' AS code UNION ALL SELECT '03' UNION ALL SELECT '04' UNION ALL SELECT '06' UNION ALL SELECT '08') colors
WHERE product.name_key = 'spinner';

-- Jig Peixinho
INSERT INTO catalog_variant (product_id, weight_grams, color_code, active)
SELECT product.id, weights.grams, colors.code, TRUE
FROM catalog_product product
CROSS JOIN (SELECT 12 AS grams UNION ALL SELECT 14) weights
CROSS JOIN (SELECT '01' AS code UNION ALL SELECT '02' UNION ALL SELECT '03' UNION ALL SELECT '04' UNION ALL SELECT '05' UNION ALL SELECT '06') colors
WHERE product.name_key = 'jig peixinho';

-- Jig Urutau
INSERT INTO catalog_variant (product_id, weight_grams, color_code, active)
SELECT product.id, weights.grams, colors.code, TRUE
FROM catalog_product product
CROSS JOIN (SELECT 12 AS grams) weights
CROSS JOIN (SELECT '01' AS code UNION ALL SELECT '02' UNION ALL SELECT '03' UNION ALL SELECT '04') colors
WHERE product.name_key = 'jig urutau';

-- Jig Articulado
INSERT INTO catalog_variant (product_id, weight_grams, color_code, active)
SELECT product.id, weights.grams, colors.code, TRUE
FROM catalog_product product
CROSS JOIN (SELECT 12 AS grams UNION ALL SELECT 14 UNION ALL SELECT 16) weights
CROSS JOIN (SELECT '01' AS code UNION ALL SELECT '02' UNION ALL SELECT '03' UNION ALL SELECT '04' UNION ALL SELECT '05' UNION ALL SELECT '06' UNION ALL SELECT '07' UNION ALL SELECT '08') colors
WHERE product.name_key = 'jig articulado';

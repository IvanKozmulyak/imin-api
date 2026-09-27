-- V151: the version id of the checkout consent sentence the buyer ticked, next to its verbatim text (V97).
-- Nullable; every earlier order has none. H2/PG-compatible.

ALTER TABLE orders ADD COLUMN marketing_opt_in_text_version VARCHAR(32) NULL;

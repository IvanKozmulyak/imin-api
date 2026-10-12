-- Ideogram V3 reports no per-image cost and nothing ever wrote this column (NULL in every row),
-- so it only advertised a cost ledger that does not exist.
ALTER TABLE poster_variants DROP COLUMN IF EXISTS ideogram_cost_eur;

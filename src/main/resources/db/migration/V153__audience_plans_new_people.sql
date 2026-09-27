-- The open-data portrait groups a plan was computed with (JSON list), so a reused plan shows the
-- groups behind its gap_exceeds_tribe. Null on plans computed before portraits. H2/PG-compatible.
ALTER TABLE audience_plans ADD COLUMN new_people TEXT;

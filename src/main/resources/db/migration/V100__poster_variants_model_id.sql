-- V100__poster_variants_model_id.sql
-- Which model actually rendered this poster.
--
-- poster_variants records the prompt, the seed, the reference images and the
-- cost, but not the thing that turned them into an image. AI Act Art.50 (in
-- application since 2 August 2026) makes provenance a disclosure obligation
-- rather than a nice-to-have, and "we generated it with AI" is not an answer to
-- "with what"; a renderer swap would also be invisible in the history.
--
-- One column, not model_id + model_version. Ideogram's API carries no version
-- field in either the request body or the response — the version lives in the
-- path (/v1/ideogram-v3/generate) — so a separate version column could only ever
-- be NULL or invented. The identifier stored here ("ideogram-v3") already names
-- the version.
--
-- Nullable, with no backfill: rows written before this migration were rendered
-- by a model nobody recorded, and writing today's identifier onto them would be
-- a guess presented as a fact.

ALTER TABLE poster_variants ADD COLUMN model_id VARCHAR(128);

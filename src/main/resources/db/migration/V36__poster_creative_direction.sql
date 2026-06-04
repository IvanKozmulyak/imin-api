-- Poster pipeline overhaul: persist the per-generation creative seed and the per-variant
-- sampled creative direction (CreativeDirectionSampler) for reproducible runs + debugging.
ALTER TABLE poster_generations ADD COLUMN creative_seed BIGINT;
ALTER TABLE poster_variants ADD COLUMN creative_direction_json TEXT;

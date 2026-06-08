-- Record the post-render vision-gate outcome per variant (Ideogram V3 render flow).
-- Separate ALTER statements: H2 (test DB) does not support comma-separated multi-ADD COLUMN.
ALTER TABLE poster_variants ADD COLUMN validation_verdict VARCHAR(16);
ALTER TABLE poster_variants ADD COLUMN validation_attempts_json TEXT;

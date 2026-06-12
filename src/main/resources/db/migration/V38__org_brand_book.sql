-- Brand book: org-level brand identity consumed by AI poster generation.
-- Columns on organizations (consistent with every prior org extension: slug V15, Stripe V18/V33).
ALTER TABLE organizations ADD COLUMN brand_name VARCHAR(120);
ALTER TABLE organizations ADD COLUMN brand_logo_url TEXT;
-- TEXT (not JSONB) for cross-engine compatibility: PG and H2 both accept TEXT, and
-- StringListJsonConverter (de)serializes the JSON array manually (same rationale as V33's
-- stripe_requirements_* lists). Order is priority: index 0 = primary accent.
ALTER TABLE organizations ADD COLUMN brand_accent_colors TEXT NOT NULL DEFAULT '[]';
ALTER TABLE organizations ADD COLUMN brand_logo_on_posters BOOLEAN NOT NULL DEFAULT TRUE;

-- Generation provenance: stamp the resolved brand onto the generation row at creation, so a
-- corrective remix (and any audit) reads the SNAPSHOT, not live org state, and "why no logo?"
-- is answerable. NULL when the generation was brandless.
ALTER TABLE poster_generations ADD COLUMN brand_snapshot TEXT;

-- Per-variant logo outcome, beside validation_verdict: NULL | 'APPLIED' | 'SKIPPED' | 'FAILED'.
-- failure_reason stays reserved for real generation failures.
ALTER TABLE poster_variants ADD COLUMN logo_composite_status VARCHAR(16);

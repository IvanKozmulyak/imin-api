-- Add slug column to organizations (nullable first for backfill)
ALTER TABLE organizations ADD COLUMN slug VARCHAR(255);

-- Backfill slugs for existing rows using a deterministic approach.
-- Uses 3-argument REGEXP_REPLACE (replaces all occurrences), compatible with both
-- PostgreSQL and H2 in PostgreSQL-compat mode.
UPDATE organizations SET slug = (
    SELECT CASE
        WHEN rn = 1 THEN normalized
        ELSE normalized || '-' || CAST(rn AS VARCHAR)
    END
    FROM (
        SELECT
            o2.id AS org_id,
            REGEXP_REPLACE(
                REGEXP_REPLACE(
                    LOWER(o2.name),
                    '[^a-z0-9]+', '-'
                ),
                '(^-|-$)', ''
            ) AS normalized,
            ROW_NUMBER() OVER (
                PARTITION BY REGEXP_REPLACE(
                    REGEXP_REPLACE(
                        LOWER(o2.name),
                        '[^a-z0-9]+', '-'
                    ),
                    '(^-|-$)', ''
                )
                ORDER BY o2.created_at, o2.id
            ) AS rn
        FROM organizations o2
    ) ranked
    WHERE ranked.org_id = organizations.id
);

-- Enforce NOT NULL (all rows now have a slug)
ALTER TABLE organizations ALTER COLUMN slug SET NOT NULL;

-- Unique constraint and index
ALTER TABLE organizations ADD CONSTRAINT uq_organizations_slug UNIQUE (slug);
CREATE INDEX ix_organizations_slug ON organizations (slug);

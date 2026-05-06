-- Replace User.name with structured first_name + last_name fields.

ALTER TABLE users ADD COLUMN first_name VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN last_name  VARCHAR(255) NOT NULL DEFAULT '';

-- Backfill: for any existing user with a single `name` like "Ada Lovelace", split on
-- the first space. Single-word names go entirely into first_name. This is best-effort —
-- structured intake starts at signup; existing rows are mostly seed/test data.
UPDATE users
SET first_name = CASE
                   WHEN POSITION(' ' IN name) > 0 THEN SUBSTRING(name FROM 1 FOR POSITION(' ' IN name) - 1)
                   ELSE name
                 END,
    last_name  = CASE
                   WHEN POSITION(' ' IN name) > 0 THEN SUBSTRING(name FROM POSITION(' ' IN name) + 1)
                   ELSE ''
                 END
WHERE name IS NOT NULL AND name <> '';

-- Drop the legacy name column once values have moved to first_name / last_name.
ALTER TABLE users DROP COLUMN name;

-- V132__audienceplan_consent_columns.sql
-- Audience plan consent inputs. Nullable or defaulted columns only; existing rows are
-- never rewritten. No tracking-consent columns: open/click tracking is off for everyone.

-- Set when the person objects to profiling (Art.21); the plan tool then keeps no taste for them.
ALTER TABLE memberships ADD COLUMN objected_profiling BOOLEAN NOT NULL DEFAULT FALSE;

-- The version of the consent sentence the person saw, so the wording can be proven later.
ALTER TABLE consent_records ADD COLUMN text_version VARCHAR(32);
-- The order a checkout consent was given on. No FK: the proof row must not depend on
-- the order row's lifecycle, and older rows carry the id only inside proof_text.
ALTER TABLE consent_records ADD COLUMN order_id UUID;

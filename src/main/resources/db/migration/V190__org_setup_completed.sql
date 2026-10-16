-- V190__org_setup_completed.sql
-- setup_completed_at: NULL until the owner of a social sign-up gives the sign-up details (POST /api/v1/org/setup).
ALTER TABLE organizations ADD COLUMN setup_completed_at TIMESTAMPTZ;

-- No default country is chosen for a social sign-up any more, so "not chosen yet" must be storable.
ALTER TABLE organizations ALTER COLUMN country DROP NOT NULL;

-- Existing orgs count as set up, except one with no Stripe account whose founding owner came through social sign-in
-- (no password, or a provider identity linked within a minute of the org): asked once, whatever its country.
UPDATE organizations o
   SET setup_completed_at = o.created_at AT TIME ZONE 'UTC'
 WHERE NULLIF(o.stripe_account_id, '') IS NOT NULL
    OR NOT EXISTS (
        SELECT 1
          FROM users u
         WHERE u.org_id = o.id
           AND u.role = 'OWNER'
           AND u.created_at < o.created_at + INTERVAL '1 minute'
           AND (u.password_hash IS NULL
                OR EXISTS (SELECT 1
                             FROM user_identities i
                            WHERE i.user_id = u.id
                              AND i.created_at < o.created_at + INTERVAL '1 minute')));

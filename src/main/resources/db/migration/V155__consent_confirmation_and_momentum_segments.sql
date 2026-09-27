-- V155: door QR and survey sign-ups wait for the address to be confirmed before either send gate counts them,
-- and Momentum's plan snapshots are marked by segments.origin instead of prebuilt_key. H2/PG-compatible.
-- A record awaits confirmation while confirmation_required is TRUE and confirmed_at is NULL. A later confirm
-- flow sets confirmed_at. Other sources never need confirmation (column default).

ALTER TABLE consent_records ADD COLUMN confirmation_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE consent_records ADD COLUMN confirmed_at TIMESTAMP WITH TIME ZONE NULL;

-- Sign-ups recorded before this migration were never confirmed either.
UPDATE consent_records SET confirmation_required = TRUE
 WHERE source IN ('door_qr', 'survey') AND confirmed_at IS NULL;

-- A member whose current email state came from such a sign-up gets back the state of their latest other email
-- record, or the never-consented default when there is none. Members with a later record keep their state.
UPDATE memberships
   SET consent_status = COALESCE((SELECT p.status FROM consent_records p
                                   WHERE p.membership_id = memberships.membership_id AND p.channel = 'email'
                                     AND (p.confirmation_required = FALSE OR p.confirmed_at IS NOT NULL)
                                   ORDER BY p.occurred_at DESC, p.id DESC LIMIT 1), 'never'),
       consent_basis = (SELECT p.lawful_basis FROM consent_records p
                         WHERE p.membership_id = memberships.membership_id AND p.channel = 'email'
                           AND (p.confirmation_required = FALSE OR p.confirmed_at IS NOT NULL)
                         ORDER BY p.occurred_at DESC, p.id DESC LIMIT 1)
 WHERE EXISTS (SELECT 1 FROM consent_records d
                WHERE d.membership_id = memberships.membership_id AND d.channel = 'email'
                  AND d.confirmation_required = TRUE AND d.confirmed_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM consent_records l
                                   WHERE l.membership_id = d.membership_id AND l.channel = 'email'
                                     AND (l.confirmation_required = FALSE OR l.confirmed_at IS NOT NULL)
                                     AND l.occurred_at >= d.occurred_at));

-- Momentum plan snapshots: origin is now the only marker.
UPDATE segments SET origin = 'momentum', prebuilt_key = NULL WHERE prebuilt_key = 'MOMENTUM_PLAN';

-- Invite-on-publish becomes at-least-once: a run claims the intent (claimed_at, attempts) and deletes it only when done,
-- so a crashed or dropped run is re-run by the sweeper. H2/PG-compatible.

ALTER TABLE audience_plan_publish_invites ADD COLUMN claimed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE audience_plan_publish_invites ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;

-- V142: door QR opt-in. Per-event switch and URL token (created on first enable, never rotated),
-- and the event a consent was given at, so the organizer can count door sign-ups per event.
-- Additive and defaulted/nullable only. H2/PG-compatible.

ALTER TABLE events ADD COLUMN door_optin_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE events ADD COLUMN door_optin_token VARCHAR(32) NULL;
CREATE UNIQUE INDEX uq_events_door_optin_token ON events (door_optin_token);

ALTER TABLE consent_records ADD COLUMN event_id UUID NULL;
CREATE INDEX idx_consent_records_event_id ON consent_records (event_id);

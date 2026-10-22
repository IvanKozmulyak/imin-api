-- V203: leave out people who already hold a live ticket for the campaign's event (send-path skip 'has_ticket').
-- Existing campaigns keep today's behaviour.
ALTER TABLE campaigns ADD COLUMN skip_ticket_holders BOOLEAN NOT NULL DEFAULT FALSE;

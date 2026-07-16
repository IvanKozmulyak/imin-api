-- V61: buyer email-marketing opt-in captured at checkout (unchecked by default).
-- Order-level snapshot, mirroring sms_marketing_opt_in (V55) and ads_consent (V60).
-- The AudienceOrderProjector reads it at fulfilment and writes the authoritative
-- channel='email' consent proof row; without this opt-in every checkout consumer
-- has no lawful basis and SendGate excludes them all.
ALTER TABLE orders ADD COLUMN marketing_opt_in BOOLEAN NOT NULL DEFAULT FALSE;

-- V55__sms_phone_collection.sql
-- Marketing Phase 3 (spec §2.2 / §4 / §7): phone + SMS-consent collection.
-- All columns NULLABLE or DEFAULT-backed so existing rows and callers are
-- unaffected (backward compatible). Zero send capability lands here — this is
-- purely the data asset the SMS channel (Phase 7) will later consume.

-- orders: buyer phone captured post-purchase + the marketing opt-in flag.
ALTER TABLE orders ADD COLUMN buyer_phone           VARCHAR(20);
ALTER TABLE orders ADD COLUMN sms_marketing_opt_in  BOOLEAN NOT NULL DEFAULT false;

-- memberships: the projected SMS contact channel + denormalized consent state,
-- mirroring the email consent_status / consent_basis pair (V48).
-- sms_consent_status: never | subscribed | unsubscribed
-- sms_consent_basis:  explicit (SMS accepts explicit ONLY — no soft opt-in, §7)
ALTER TABLE memberships ADD COLUMN phone_e164          VARCHAR(20);
ALTER TABLE memberships ADD COLUMN sms_consent_status  VARCHAR(16) NOT NULL DEFAULT 'never';
ALTER TABLE memberships ADD COLUMN sms_consent_basis   VARCHAR(16);

-- suppression_entries: make suppression channel-aware (email today, sms later).
-- phone_e164 is the SMS-side suppression key, mirroring normalized_email for email.
-- Schema-only groundwork for Phase 7; nothing writes phone suppression yet.
ALTER TABLE suppression_entries ADD COLUMN channel     VARCHAR(8) NOT NULL DEFAULT 'email';
ALTER TABLE suppression_entries ADD COLUMN phone_e164  VARCHAR(20);

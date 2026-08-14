-- V91__buyer_onboarding_consent.sql
-- The two toggles on the finish-registration step.
--
-- 1. TERMS AND PRIVACY — mandatory. Recording WHEN and WHICH VERSION is the
--    whole point: "they accepted" is unfalsifiable without a timestamp and a
--    version, and a terms change that nobody re-accepted is exactly the case
--    the record has to be able to answer.
--
-- 2. IMIN'S OWN MARKETING — optional, and this is the column V85 created for a
--    sender that did not exist ("News from IMIN", cut for want of a lawful
--    basis). The basis is what this migration adds: an explicit, unticked,
--    separately-recorded opt-in with the on-screen sentence stored beside it.
--
-- WHY THIS IS NOT THE ORGANIZER TOGGLE. Consent must name its controller. imin
-- is one, so imin may take consent for its own mail at signup. Organizers are
-- separate controllers the buyer has not met yet at that moment, which is why
-- organizer_updates stays derived from per-membership consent captured at
-- checkout, and why nothing here touches it.
ALTER TABLE buyer_accounts ADD COLUMN terms_accepted_at TIMESTAMP WITH TIME ZONE NULL;
ALTER TABLE buyer_accounts ADD COLUMN terms_version     VARCHAR(32)              NULL;

-- Proof for the marketing opt-in, kept next to the flag it justifies rather
-- than in consent_records: that table is keyed by membership_id and scoped to
-- an organizer, and this consent has neither — it is between the buyer and imin.
ALTER TABLE buyer_notification_preferences ADD COLUMN product_news_at    TIMESTAMP WITH TIME ZONE NULL;
ALTER TABLE buyer_notification_preferences ADD COLUMN product_news_proof TEXT                     NULL;

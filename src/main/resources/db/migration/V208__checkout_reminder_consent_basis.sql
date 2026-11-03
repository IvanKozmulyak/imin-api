-- How the address was kept: the buyer ticked the organizer's box (EXPLICIT), saw the paid-checkout notice
-- and did not tick its refuse box (NOTICE), or ticked that refuse box (REFUSED: a NOT_SENT row that only stops
-- a reminder for the night). Every earlier row came from a tick.
ALTER TABLE checkout_reminders
    ADD COLUMN consent_basis VARCHAR(16) NOT NULL DEFAULT 'EXPLICIT'
        CHECK (consent_basis IN ('EXPLICIT', 'NOTICE', 'REFUSED'));

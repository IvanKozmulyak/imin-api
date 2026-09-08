-- Organizer email-verification codes: store a peppered digest, never the digits.
--
-- The column was VARCHAR(4) holding the code in plaintext, so anyone who could
-- read the table held a live credential for every unverified organizer account
-- (the /auth/verify-email response is a session token). The buyer side already
-- solved this in V84: HMAC-SHA256(IMIN_BUYER_CODE_SECRET, code) as 64 hex chars.
--
-- `code` is made nullable rather than dropped: rows written before this deploy
-- keep their plaintext value and stay verifiable until they expire (10 minutes),
-- so a rolling deploy does not invalidate codes already in people's inboxes.
-- Nothing writes `code` after this migration.
ALTER TABLE email_verification_codes ADD COLUMN code_hash VARCHAR(64);
ALTER TABLE email_verification_codes ALTER COLUMN code DROP NOT NULL;

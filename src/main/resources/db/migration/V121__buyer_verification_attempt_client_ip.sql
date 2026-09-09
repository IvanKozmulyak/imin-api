-- The verification lockout counted failures by the address in the REQUEST BODY
-- and nothing else, so the budget belonged to the victim rather than to the
-- attacker: ten wrong codes posted at victim@x.com by anyone locked that
-- address out of being verified for the whole window, repeatable hourly at no
-- cost, for as long as the 72-hour unverified-claim sweep kept the real owner
-- from finishing signup. Recording the caller lets the counter be keyed on
-- (address, client IP): the person making the failures is the person who pays.
--
-- Nullable, and rows written before this migration keep a NULL that no lookup
-- matches. That is deliberate — the sweeper drops rows after 24 hours, so the
-- gap closes on its own, and a backfilled sentinel would fabricate a caller.
-- 45 characters: an IPv6 address with an IPv4 tail, the widest form there is.
ALTER TABLE buyer_verification_attempts ADD COLUMN client_ip VARCHAR(45);

CREATE INDEX ix_bva_email_ip_time
    ON buyer_verification_attempts (email_normalized, client_ip, attempted_at);

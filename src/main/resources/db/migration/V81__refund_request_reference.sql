-- V81__refund_request_reference.sql
-- Short, human-dictatable reference for a buyer refund request.
--
-- The submit response previously returned only the UUID id. A person reading their
-- refund receipt to support over the phone cannot dictate a UUID (or a fragment of
-- one, which is not even unique). This adds a code they can read aloud:
--
--   REQ-8K2M-26     REQ- + 4 random chars + the 2-digit year
--
-- Alphabet is 32 unambiguous uppercase symbols — 23456789ABCDEFGHJKLMNPQRSTUVWXYZ —
-- with 0/O and 1/I removed so "was that a zero or an oh?" never happens. Generated in
-- RefundReferenceGenerator; uniqueness is enforced by the index at the bottom of this
-- file, not by hope.
--
-- BACKFILL. Existing rows get a deterministic code derived from their own UUID:
-- 8 hex digits mapped through a 16-symbol slice of the same alphabet, laid out as
-- REQ-XXXX-XXXX. The 4-char tail makes legacy codes structurally distinct from
-- generated ones (whose tail is a 2-digit year), so the two families cannot collide
-- with each other, and 16^8 (4.3e9) makes a collision within the backfill itself
-- vanishingly unlikely at this table's size. SUBSTRING/TRANSLATE/CAST are used
-- because all three behave identically on Postgres and on H2 in PG-compat mode.

ALTER TABLE refund_requests ADD COLUMN reference VARCHAR(16);

UPDATE refund_requests
   SET reference = 'REQ-'
       || TRANSLATE(SUBSTRING(CAST(id AS VARCHAR), 1, 4), '0123456789abcdef', '23456789ABCDEFGH')
       || '-'
       || TRANSLATE(SUBSTRING(CAST(id AS VARCHAR), 10, 4), '0123456789abcdef', '23456789ABCDEFGH')
 WHERE reference IS NULL;

ALTER TABLE refund_requests ALTER COLUMN reference SET NOT NULL;

-- Uniqueness AND the operator-side lookup index: GET /orgs/{orgId}/refund-requests
-- takes a ?search= that resolves an exact reference, so this index is the read path
-- for "customer quoted me REQ-8K2M-26".
CREATE UNIQUE INDEX uq_refund_requests_reference ON refund_requests (reference);

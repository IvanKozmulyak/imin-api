-- V103__meta_capi_events_email_sha256_nullable.sql
-- Let an Art.17 erasure redact a Meta CAPI send record in place.
--
-- V60 made email_sha256 NOT NULL, which was right for a write path that always
-- has an address to hash. It is wrong for the erasure path: a hashed email is
-- still personal data — it is a pseudonym Meta can re-identify — so an erasure
-- has to remove it, and the NOT NULL left only the choice of deleting the whole
-- row.
--
-- The row is worth keeping. It is the evidence that a disclosure to Meta
-- happened for an order we retain under the accounting exemption; deleting it
-- would destroy the record of the disclosure while the disclosure itself has
-- already occurred. NULL here means exactly "this send was redacted", which no
-- sentinel hash could say without being a fabricated value.
--
-- Nothing reads email_sha256 except MetaCapiSender, which only ever loads rows
-- in 'pending' status; a redacted row has already been sent or has already
-- failed out. The writer still always supplies it.

ALTER TABLE meta_capi_events ALTER COLUMN email_sha256 DROP NOT NULL;

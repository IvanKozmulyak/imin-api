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
-- ============================================================================
-- THE COLUMN SHIPS NULLABLE. THIS IS DELIBERATE — DO NOT ADD `SET NOT NULL` HERE.
-- ============================================================================
-- Railway overlaps deploys: Flyway runs on the NEW container while the OLD one is
-- still serving live traffic, and there is no separate migration step to hide behind.
-- Old code does not set `reference`. A `NOT NULL` here would mean that every refund
-- submit served by the old instance between "V81 commits" and "old instance drains"
-- fails on a constraint the old code cannot satisfy — on the live payments path, and
-- (before the constraint-aware catch in RefundRequestService) reported to the buyer as
-- "a refund request is already open for this order" when none is.
--
-- So: expand now, contract later. A follow-up migration adds
--     ALTER TABLE refund_requests ALTER COLUMN reference SET NOT NULL;
-- once no instance predating V81 can still be running — i.e. any deploy after this one.
-- It must also backfill anything the overlap window let through:
--     UPDATE refund_requests SET reference = <same expression as below> WHERE reference IS NULL;
-- Until then the application is what guarantees a reference: RefundRequest.reference is
-- mapped `nullable = false`, so a null is refused before it reaches the database.
--
-- BACKFILL. Existing rows get REQ-XXXX-XXXX, eight symbols of the same alphabet, from a
-- per-row ordinal rather than from randomness. The 4-char tail makes legacy codes
-- structurally distinct from generated ones (whose tail is a 2-digit year), so the two
-- families cannot collide with each other; the ordinal makes the legacy family internally
-- collision-free BY CONSTRUCTION rather than probabilistically.
--
-- That matters because the UNIQUE index at the bottom runs in this same transaction: a
-- single collision does not lose a code, it aborts the migration and the application does
-- not start. The previous version derived 32 bits from each row's v4 UUID, which is a
-- birthday problem — ~1.2% chance of at least one collision at 10k rows, rising with the
-- square of the table. A sequence has no such failure mode at any size.
--
-- SUBSTRING/MOD/CAST/CREATE SEQUENCE/nextval all behave identically on PostgreSQL 17 and
-- on H2 2.4 in PostgreSQL-compatibility mode, which is what backs the test suite.

ALTER TABLE refund_requests ADD COLUMN reference VARCHAR(16);

-- One ordinal per row needing a code. A temporary column rather than inlining nextval()
-- eight times: the eight symbol expressions below must all read the SAME number.
ALTER TABLE refund_requests ADD COLUMN reference_ordinal BIGINT;
CREATE SEQUENCE refund_reference_backfill_seq START WITH 1 INCREMENT BY 1;

UPDATE refund_requests
   SET reference_ordinal = nextval('refund_reference_backfill_seq')
 WHERE reference IS NULL;

-- Base-32 over the unambiguous alphabet, most significant symbol first. 32^8 = 1.1e12
-- distinct codes, so the layout does not run out before the VARCHAR(16) does.
UPDATE refund_requests
   SET reference = 'REQ-'
       || SUBSTRING('23456789ABCDEFGHJKLMNPQRSTUVWXYZ', CAST(MOD(reference_ordinal / 34359738368, 32) AS INT) + 1, 1)
       || SUBSTRING('23456789ABCDEFGHJKLMNPQRSTUVWXYZ', CAST(MOD(reference_ordinal / 1073741824,  32) AS INT) + 1, 1)
       || SUBSTRING('23456789ABCDEFGHJKLMNPQRSTUVWXYZ', CAST(MOD(reference_ordinal / 33554432,    32) AS INT) + 1, 1)
       || SUBSTRING('23456789ABCDEFGHJKLMNPQRSTUVWXYZ', CAST(MOD(reference_ordinal / 1048576,     32) AS INT) + 1, 1)
       || '-'
       || SUBSTRING('23456789ABCDEFGHJKLMNPQRSTUVWXYZ', CAST(MOD(reference_ordinal / 32768,       32) AS INT) + 1, 1)
       || SUBSTRING('23456789ABCDEFGHJKLMNPQRSTUVWXYZ', CAST(MOD(reference_ordinal / 1024,        32) AS INT) + 1, 1)
       || SUBSTRING('23456789ABCDEFGHJKLMNPQRSTUVWXYZ', CAST(MOD(reference_ordinal / 32,          32) AS INT) + 1, 1)
       || SUBSTRING('23456789ABCDEFGHJKLMNPQRSTUVWXYZ', CAST(MOD(reference_ordinal,               32) AS INT) + 1, 1)
 WHERE reference_ordinal IS NOT NULL;

ALTER TABLE refund_requests DROP COLUMN reference_ordinal;
DROP SEQUENCE refund_reference_backfill_seq;

-- Uniqueness AND the operator-side lookup index: GET /orgs/{orgId}/refund-requests
-- takes a ?search= that resolves an exact reference, so this index is the read path
-- for "customer quoted me REQ-8K2M-26". NULLs are distinct in both Postgres and H2, so
-- this coexists with the nullable column above.
CREATE UNIQUE INDEX uq_refund_requests_reference ON refund_requests (reference);

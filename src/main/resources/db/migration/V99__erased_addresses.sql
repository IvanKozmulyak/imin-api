-- V99__erased_addresses.sql
-- Erasure ledger — the tombstone that makes Art.17 erasure survive the projectors.
--
-- WHY THIS EXISTS. AudienceErasureJob (02:00) and BuyerAccountErasureJob (02:30)
-- delete the Consumer + Membership rows for an erased person. AudienceBackfillJob
-- then runs at 03:00 — and on every application start — walking
-- orderRepository.findDistinctOrgAndEmailPairs() over ALL orders and re-upserting
-- a Consumer (email + name) and a Membership (spend, attendance, RFM) for every
-- pair it finds. Orders are retained under the accounting exemption, so the erased
-- person's address is still there, so the backfill rebuilt them thirty minutes
-- after they were erased. Marketing never resumed (emailOptIn stays false and
-- marketing_optouts survives), but the audience record itself came back, which
-- means the erasure was void in substance.
--
-- WHAT A ROW MEANS. "This address asked to be forgotten; do not reconstruct an
-- audience record for it from retained order history."
--
--   org_id NOT NULL — one organizer erased their copy (DSAR, org-scoped). Other
--                     orgs' records for the same person are their data and are
--                     untouched, exactly as DsarService.executeErase already
--                     scopes the cascade.
--   org_id NULL     — the person erased their whole imin account
--                     (BuyerAccountErasureService): platform-wide, every org.
--
-- The ledger holds the normalized address and nothing else. That is not "more
-- personal data kept after an erasure" in any useful sense — the same address is
-- already retained on orders under the invoicing exemption, and this row exists
-- solely to stop that retained copy being re-expanded into a marketing profile.
--
-- NOT UNIQUE. Re-erasure after a new purchase is legitimate and must append
-- rather than fail; the readers are existence checks, so duplicates are harmless.

CREATE TABLE erased_addresses (
    id               UUID         PRIMARY KEY,
    org_id           UUID,
    email_normalized VARCHAR(254) NOT NULL,
    erased_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX ix_erased_addresses_email     ON erased_addresses (email_normalized);
CREATE INDEX ix_erased_addresses_org_email ON erased_addresses (org_id, email_normalized);

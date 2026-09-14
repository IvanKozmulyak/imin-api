-- V131__disputes_payment_intent_id_index.sql
-- Paid fulfilment now asks "is there an unattributed dispute for this PaymentIntent?" on every
-- issued order (DisputeIngestService.attachOrphansForOrder), and the 5-minute attribution sweep
-- asks the same question in reverse. Without this the first is a sequential scan inside the
-- payment_intent.succeeded transaction, which already holds a row lock on the ticket tier.
--
-- Plain rather than partial (WHERE order_id IS NULL): tests run Flyway on H2, which has no
-- partial indexes, and the table is small enough that the extra entries cost nothing.
CREATE INDEX disputes_pi_idx ON disputes (stripe_payment_intent_id);

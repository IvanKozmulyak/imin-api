-- V111__settlements_last_event_at.sql
-- Track A: make the settlements read-model tolerant of OUT-OF-ORDER Stripe events.
--
-- Stripe does not guarantee delivery order, and the processed_webhook_events
-- dedup marker is written in the SAME transaction as the handler, so a handler
-- that fails rolls its marker back and Stripe's later retry re-processes that
-- event id from scratch. Concretely: transfer.created 500s, charge.refunded
-- lands and sets the row 'reversed', then the transfer.created retry arrives
-- hours later and drags the same row back to 'pending' — the bucket the payouts
-- summary tile sums.
--
-- last_event_at records the Stripe `event.created` of the delivery that last
-- wrote the row, so an older event can be recognised and ignored. NULL means
-- "written before this column existed" (every existing row) and is treated as
-- "no ordering information", which reproduces today's behaviour.
--
-- Forward-only, additive, H2/PG-compatible.

ALTER TABLE settlements
  ADD COLUMN last_event_at TIMESTAMP WITH TIME ZONE;

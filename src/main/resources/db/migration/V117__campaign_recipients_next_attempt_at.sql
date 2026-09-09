-- mkt-core-11: per-row send backoff. A failed batch left its rows 'pending' and the drain
-- loop re-claimed the SAME rows immediately (ORDER BY id LIMIT 100), re-POSTing an identical
-- batch to Resend within milliseconds — three times before attempt_count exhausted. Resend's
-- batch API is not all-or-nothing, so a partial outage became a triple send for the accepted
-- portion. claimPendingBatch now skips rows whose next_attempt_at is still in the future.
-- NULL = claimable now, so every existing row keeps its current behaviour.
ALTER TABLE campaign_recipients
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE;

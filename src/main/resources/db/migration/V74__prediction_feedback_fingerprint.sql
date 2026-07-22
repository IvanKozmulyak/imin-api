-- V74: Dismissal memory (predictor spec §4.3, task 86cav47a5).
-- A dismissed recommendation must not return unless MATERIALLY changed. "Materially" is
-- defined by a fingerprint = hash(actionType + tier ref + priceMinor bucket) computed
-- ON WRITE of each dismissed/restored feedback row and matched at serve time against the
-- fingerprint of every currently-rendered recommendation. Same-bucket price nudges collide
-- (suppressed); a different bucket, tier, or action type is a new fingerprint (returns).
--
-- Compute-on-write: the fingerprint is derived from the recommendation as it existed in the
-- ledger render the feedback targeted, so serve-time filtering is a pure string compare with
-- no re-derivation of historical recommendations.
--
-- Nullable: EXECUTED rows and any pre-V74 rows carry no fingerprint (they never suppress).
-- The V70 feedback_type column has NO check constraint, so adding the 'restored' wire value
-- (FeedbackType.RESTORED — clears a prior dismissal) needs no constraint change here.
ALTER TABLE prediction_feedback ADD COLUMN fingerprint VARCHAR(64);

-- Serve-time dismissal filter groups an event's feedback by fingerprint and takes the latest
-- row per fingerprint (dismissed suppresses; restored/executed do not). Index the lookup path.
CREATE INDEX ix_prediction_feedback_fingerprint ON prediction_feedback (event_id, fingerprint);

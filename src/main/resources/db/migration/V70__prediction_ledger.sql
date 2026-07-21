-- V70: Prediction ledger (predictor spec §5, §7.2) — one of the four NEVER-CUTTABLE items.
-- Every rendered prediction is persisted here BEFORE it reaches a screen (§5: "unlogged
-- predictions are a bug by definition"). The ledger is simultaneously the audit trail, the
-- evaluation set, and the future training set — the only reason day-one LLM shipping is
-- defensible, because from the first real event accuracy is measured, not argued.
--
-- Append-only. Rows are never mutated except by the monthly scoring job, which fills the
-- outcome-join columns once an event completes.
--
-- WRITE-BEFORE-RENDER IS A CONTRACT: later phases MUST call PredictionLedgerService.record(...)
-- and get a row id back before returning a prediction to any surface.
--
-- Versioning discipline (§7.3): model_id + prompt_version (semver) are stamped on every row.
-- A prompt change is a version bump, or eval comparability dies silently.
--
-- event_id / org_id carry no DB foreign key (app-scoped, matching V58/V68/V69): the recorder
-- only ever writes for a real event it just scored.

CREATE TABLE prediction_ledger (
    id                   UUID PRIMARY KEY,
    event_id             UUID NOT NULL,
    org_id               UUID NOT NULL,
    surface              VARCHAR(16)  NOT NULL,          -- pre_publish | reforecast | actions
    stage                SMALLINT     NOT NULL,          -- 0 (LLM) | 1 (pacing) | 2 (learned)
    model_id             VARCHAR(128) NOT NULL,          -- e.g. anthropic/claude-sonnet-4.6
    prompt_version       VARCHAR(32)  NOT NULL,          -- semver, e.g. 1.0.0
    input_snapshot_hash  VARCHAR(64)  NOT NULL,          -- cache key = hash of the versioned input snapshot
    comparables_json     TEXT         NOT NULL DEFAULT '{}',  -- {ids:[...], clusterSize, relaxation}
    output_json          TEXT         NOT NULL DEFAULT '{}',  -- the full PredictionResult rendered
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    -- ---- outcome-join columns (nullable; filled by PredictionScoringJob at event completion) ----
    actual_sold          INT,
    actual_attendance    INT,
    outcome_joined_at    TIMESTAMP WITH TIME ZONE,
    brier_component      NUMERIC(8,6),   -- sell-out Brier term (minimal now; real formula in the Score phase)
    ape                  NUMERIC(10,6)   -- attendance absolute percentage error
);

CREATE INDEX ix_prediction_ledger_event    ON prediction_ledger (event_id, surface, created_at);
CREATE INDEX ix_prediction_ledger_org      ON prediction_ledger (org_id, created_at);
-- Scoring job scans rows awaiting an outcome join.
CREATE INDEX ix_prediction_ledger_unjoined ON prediction_ledger (outcome_joined_at);

-- Feedback signal (§4.3): dismissals and executions of individual recommendations.
-- Modeled as a CHILD table rather than columns on prediction_ledger — a single render
-- carries up to 3 recommendations, each independently dismissible/executable across
-- sessions, so single columns cannot represent it. Each feedback row references the
-- ledger render it came from AND the specific recommendation id inside that render's
-- output_json. "Visible in the ledger" (§4.3) is satisfied by the ledger_id join.
CREATE TABLE prediction_feedback (
    id                UUID PRIMARY KEY,
    ledger_id         UUID NOT NULL,            -- the prediction_ledger render this recommendation came from
    event_id          UUID NOT NULL,            -- denormalized for per-event feedback queries
    recommendation_id VARCHAR(128) NOT NULL,    -- id of the recommendation inside output_json
    feedback_type     VARCHAR(16)  NOT NULL,    -- dismissed | executed
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX ix_prediction_feedback_ledger ON prediction_feedback (ledger_id);
CREATE INDEX ix_prediction_feedback_event  ON prediction_feedback (event_id, recommendation_id);

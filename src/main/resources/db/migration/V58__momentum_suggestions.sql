-- V58: Momentum Engine suggestions (spec §6.2).
-- One row per fired trigger. status='suggested' is the only live state.
-- NOTE: the spec's "UNIQUE(event_id, trigger_type, status) WHERE status='suggested'"
-- is a Postgres partial unique index; H2 (test) has no partial unique indexes
-- (see V50 suppression). So uniqueness of the single LIVE suggestion is enforced
-- in MomentumEvaluator inside the insert transaction. Here we add only plain
-- indexes for query speed.
-- NOTE: event_id is intentionally NOT a DB foreign key to events(id). The evaluator
-- only ever inserts for a real, LIVE event it just read, and stale rows are handled
-- in the app layer: MomentumEvaluator.expireStale() flips any 'suggested' row whose
-- event has started OR no longer resolves (events.findActive empty) to 'expired'.
-- Omitting the FK also keeps MomentumRepositoryTest (Task 2) — which saves rows with
-- random UUID event_ids and no seeded Event — free of DataIntegrityViolationException;
-- the MomentumTestSupport seeder that creates real Events is not built until Task 6.
CREATE TABLE momentum_suggestions (
    id               UUID         PRIMARY KEY,
    org_id           UUID         NOT NULL,
    event_id         UUID         NOT NULL,   -- app-scoped to a real event; no DB FK (see note below)
    trigger_type     VARCHAR(24)  NOT NULL,   -- launch_push|slump|urgency_72h|sold_out
    status           VARCHAR(16)  NOT NULL DEFAULT 'suggested', -- suggested|approved|dismissed|expired
    metrics_snapshot TEXT         NOT NULL,   -- JSON: velocity/sell-through/days-out at eval time
    draft_payload    TEXT         NOT NULL,   -- JSON: subject/body_md/segment_id/poster_url/why
    campaign_id      UUID,                    -- set on approve
    suggested_at     TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT now(),
    acted_at         TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT now()
);

-- Query paths: (1) find the live suggestion for an (event, trigger) — the
-- app-layer uniqueness check; (2) list an org's suggestions by status;
-- (3) cooldown lookup by (event, trigger, suggested_at).
CREATE INDEX ix_momentum_event_trigger_status
    ON momentum_suggestions (event_id, trigger_type, status);
CREATE INDEX ix_momentum_org_status
    ON momentum_suggestions (org_id, status, suggested_at);

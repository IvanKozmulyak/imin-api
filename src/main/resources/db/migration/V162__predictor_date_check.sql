-- V162: "Check a date" schema: request, candidate dates, findings, reference calendar, job queue,
-- weekly genre counts, org connectors; the ledger accepts event-less DATE_CHECK renders.

CREATE TABLE date_check (
    id UUID PRIMARY KEY, org_id UUID NOT NULL,
    created_by UUID NOT NULL,      -- no FK: TeamService.countRetainedReferences lists every users FK
    city VARCHAR(100) NOT NULL, country VARCHAR(2) NOT NULL, genre_family VARCHAR(64) NOT NULL,
    sub_genre VARCHAR(64), capacity INT, price_minor BIGINT, format VARCHAR(32),
    start_hour SMALLINT, end_hour SMALLINT, lineup_json TEXT, known_events_json TEXT,
    assumptions_json TEXT NOT NULL DEFAULT '[]', research BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL, question_bank_version VARCHAR(32) NOT NULL, event_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_date_check_org FOREIGN KEY (org_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_date_check_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE SET NULL,
    CONSTRAINT ck_date_check_status CHECK (status IN ('pending','running','done','partial','failed'))
);
CREATE INDEX ix_date_check_org_created ON date_check (org_id, created_at);
CREATE INDEX ix_date_check_event ON date_check (event_id);

CREATE TABLE date_check_date (
    id UUID PRIMARY KEY, date_check_id UUID NOT NULL, candidate_date DATE NOT NULL,
    verdict VARCHAR(16) NOT NULL, risk_score SMALLINT NOT NULL, opp_score SMALLINT NOT NULL,
    coverage NUMERIC(4,3) NOT NULL, rank_order SMALLINT, actions_json TEXT NOT NULL DEFAULT '[]',
    CONSTRAINT fk_date_check_date_check FOREIGN KEY (date_check_id) REFERENCES date_check (id) ON DELETE CASCADE,
    CONSTRAINT uq_date_check_date UNIQUE (date_check_id, candidate_date),
    CONSTRAINT ck_date_check_date_verdict CHECK (verdict IN ('good','adjust','move','not_enough_data')),
    CONSTRAINT ck_date_check_date_risk CHECK (risk_score BETWEEN 0 AND 10),
    CONSTRAINT ck_date_check_date_opp CHECK (opp_score BETWEEN 0 AND 10),
    CONSTRAINT ck_date_check_date_coverage CHECK (coverage BETWEEN 0 AND 1)
);

CREATE TABLE date_check_finding (
    id UUID PRIMARY KEY, date_check_date_id UUID NOT NULL, question_id VARCHAR(16) NOT NULL,
    kind VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL, strength SMALLINT NOT NULL, weight SMALLINT NOT NULL,
    source_kind VARCHAR(16) NOT NULL, time_window VARCHAR(8) NOT NULL, stop_factor BOOLEAN NOT NULL DEFAULT FALSE,
    facts_json TEXT NOT NULL DEFAULT '{}', url VARCHAR(2048), quote VARCHAR(1000), fetched_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_date_check_finding_date FOREIGN KEY (date_check_date_id) REFERENCES date_check_date (id) ON DELETE CASCADE,
    CONSTRAINT ck_date_check_finding_kind CHECK (kind IN ('risk','opportunity')),
    CONSTRAINT ck_date_check_finding_status CHECK (status IN ('found','clear','not_checked')),
    CONSTRAINT ck_date_check_finding_source_kind CHECK (source_kind IN ('structured','internal','web','organizer','input')),
    CONSTRAINT ck_date_check_finding_window CHECK (time_window IN ('night','week','month')),
    CONSTRAINT ck_date_check_finding_strength CHECK (strength BETWEEN 0 AND 3),
    CONSTRAINT ck_date_check_finding_weight CHECK (weight BETWEEN 1 AND 3),
    CONSTRAINT ck_date_check_finding_soft_strength CHECK (source_kind IN ('structured','internal') OR strength <= 2),
    CONSTRAINT ck_date_check_finding_stop_source CHECK (stop_factor = FALSE OR source_kind IN ('structured','internal'))
);
CREATE INDEX ix_date_check_finding_date ON date_check_finding (date_check_date_id);

CREATE TABLE reference_calendar (
    id UUID PRIMARY KEY, country VARCHAR(2) NOT NULL,
    region VARCHAR(16) NOT NULL DEFAULT '',   -- '' = whole country; NULL would break the unique key
    calendar_date DATE NOT NULL, end_date DATE, kind VARCHAR(16) NOT NULL, name VARCHAR(255) NOT NULL,
    source_url VARCHAR(2048) NOT NULL, synced_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_reference_calendar_entry UNIQUE (country, region, calendar_date, kind, name),
    CONSTRAINT ck_reference_calendar_kind CHECK (kind IN ('holiday','school','pont','dst','hijri','fixture')),
    CONSTRAINT ck_reference_calendar_range CHECK (end_date IS NULL OR end_date >= calendar_date)
);

CREATE TABLE predictor_job (
    id UUID PRIMARY KEY, kind VARCHAR(64) NOT NULL, payload_json TEXT NOT NULL DEFAULT '{}',
    status VARCHAR(16) NOT NULL, attempts INT NOT NULL DEFAULT 0,
    run_after TIMESTAMP WITH TIME ZONE NOT NULL, locked_until TIMESTAMP WITH TIME ZONE, last_error VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_predictor_job_status CHECK (status IN ('queued','running','done','failed'))
);
CREATE INDEX ix_predictor_job_status_run_after ON predictor_job (status, run_after);

-- ODbL-derived weekly counts; kept separate so the licence stays separable.
CREATE TABLE genre_week_count (
    id UUID PRIMARY KEY, city_key VARCHAR(100) NOT NULL, genre_family VARCHAR(64) NOT NULL,
    sub_genre VARCHAR(64) NOT NULL DEFAULT '', week_start DATE NOT NULL, event_count INT NOT NULL,
    sources_json TEXT NOT NULL DEFAULT '[]', updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_genre_week_count UNIQUE (city_key, genre_family, sub_genre, week_start),
    CONSTRAINT ck_genre_week_count_nonneg CHECK (event_count >= 0)
);

CREATE TABLE org_connector (
    id UUID PRIMARY KEY, org_id UUID NOT NULL, kind VARCHAR(16) NOT NULL, token_enc TEXT NOT NULL,
    scopes VARCHAR(512), connected_by UUID NOT NULL,   -- no FK, same reason as date_check.created_by
    connected_at TIMESTAMP WITH TIME ZONE NOT NULL, revoked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_org_connector_org FOREIGN KEY (org_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT ck_org_connector_kind CHECK (kind IN ('shotgun','dice','instagram'))
);
CREATE INDEX ix_org_connector_org_kind ON org_connector (org_id, kind);

-- A date check has no event yet; surface is @Enumerated(STRING), so the literal is the enum name.
ALTER TABLE prediction_ledger ALTER COLUMN event_id DROP NOT NULL;
ALTER TABLE prediction_ledger ADD COLUMN date_check_id UUID;
ALTER TABLE prediction_ledger ADD COLUMN question_bank_version VARCHAR(32);
ALTER TABLE prediction_ledger ADD COLUMN tokens_in INT;
ALTER TABLE prediction_ledger ADD COLUMN tokens_out INT;
ALTER TABLE prediction_ledger ADD COLUMN cost_usd NUMERIC(12,6);
ALTER TABLE prediction_ledger ADD COLUMN searches INT;
ALTER TABLE prediction_ledger ADD CONSTRAINT ck_prediction_ledger_event_or_date_check
    CHECK (event_id IS NOT NULL OR surface = 'DATE_CHECK');
ALTER TABLE prediction_ledger ADD CONSTRAINT ck_prediction_ledger_date_check_id
    CHECK (date_check_id IS NOT NULL OR surface <> 'DATE_CHECK');
CREATE INDEX ix_prediction_ledger_date_check ON prediction_ledger (date_check_id);

ALTER TABLE events ADD COLUMN sub_genre VARCHAR(64);
ALTER TABLE events ADD COLUMN date_check_id UUID;
ALTER TABLE events ADD CONSTRAINT fk_events_date_check FOREIGN KEY (date_check_id) REFERENCES date_check (id) ON DELETE SET NULL;
CREATE INDEX ix_events_date_check ON events (date_check_id);

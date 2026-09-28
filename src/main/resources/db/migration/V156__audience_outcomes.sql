-- V156__audience_outcomes.sql
-- After-event outcomes of audience plan experiments, and the calibration they feed. Aggregates only: no table here
-- holds a membership id, so DSAR erasure leaves them as they are. H2/PG-compatible: no native enum, named constraints.

-- One row per experiment arm, written at D+1 and overwritten at D+7.
CREATE TABLE audience_outcomes (
    experiment_id    UUID         PRIMARY KEY REFERENCES audience_experiments(id) ON DELETE CASCADE,
    org_id           UUID         NOT NULL,
    event_id         UUID         NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    plan_segment_id  UUID,
    -- null when the experiment has no plan segment
    class            VARCHAR(16),
    genre_fit        VARCHAR(8),
    arm              VARCHAR(16)  NOT NULL,
    -- d1 | d7
    phase            VARCHAR(4)   NOT NULL,
    members          INT          NOT NULL,
    sent             INT          NOT NULL,
    bought           INT          NOT NULL,
    -- bought among the members who were sent the email (feeds calibration)
    sent_bought      INT          NOT NULL,
    tickets          INT          NOT NULL,
    attended         INT          NOT NULL,
    unsubscribed     INT          NOT NULL,
    complained       INT          NOT NULL,
    computed_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX ix_audience_outcomes_event ON audience_outcomes (event_id);

-- Per-event figures of the same run.
CREATE TABLE audience_event_outcomes (
    event_id           UUID         PRIMARY KEY REFERENCES events(id) ON DELETE CASCADE,
    org_id             UUID         NOT NULL,
    phase              VARCHAR(4)   NOT NULL,
    -- distinct buyers with no membership in the org before the first invitation assignment; null when no
    -- assignment was left to date that first invitation
    new_guests         INT,
    door_closed_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    computed_at        TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Rebuilt from audience_outcomes after each collection. scope 'imin' rows sum every org and carry the nil UUID as
-- org_id, so the unique key also holds for them (NULLs would be distinct).
CREATE TABLE response_calibration (
    id          UUID         PRIMARY KEY,
    -- org | imin
    scope       VARCHAR(8)   NOT NULL,
    org_id      UUID         NOT NULL,
    class       VARCHAR(16)  NOT NULL,
    genre_fit   VARCHAR(8)   NOT NULL,
    arm         VARCHAR(16)  NOT NULL,
    -- invitation arms: members who were sent the email; holdout: held-out members
    n           INT          NOT NULL,
    bought      INT          NOT NULL,
    events      INT          NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ux_response_calibration UNIQUE (scope, org_id, class, genre_fit, arm),
    CONSTRAINT ck_response_calibration_scope CHECK (scope IN ('org', 'imin'))
);

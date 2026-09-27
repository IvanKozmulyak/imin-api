-- V136__audience_experiments.sql
-- Audience plan experiments: one row per arm of an invited plan segment, and who was put in
-- which arm. Holdout members of an event are never emailed about that event by any campaign.
-- H2/PG-compatible: no native enum, no unnamed CHECK. plan_id / plan_segment_id get their
-- foreign keys when the plan tables exist.

CREATE TABLE audience_experiments (
    id               UUID         PRIMARY KEY,
    org_id           UUID         NOT NULL,
    event_id         UUID         NOT NULL,
    plan_id          UUID,
    plan_segment_id  UUID,
    -- holdout | launch | d3 | slump | two_emails
    arm              VARCHAR(16)  NOT NULL,
    campaign_id      UUID         REFERENCES campaigns(id) ON DELETE SET NULL,
    members          INT          NOT NULL DEFAULT 0,
    seed             BIGINT       NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX ix_audience_experiments_org_event ON audience_experiments (org_id, event_id);

CREATE TABLE audience_assignments (
    experiment_id    UUID         NOT NULL REFERENCES audience_experiments(id) ON DELETE CASCADE,
    membership_id    UUID         NOT NULL REFERENCES memberships(membership_id) ON DELETE CASCADE,
    arm              VARCHAR(16)  NOT NULL,
    assigned_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_audience_assignments PRIMARY KEY (experiment_id, membership_id)
);

CREATE INDEX ix_audience_assignments_membership ON audience_assignments (membership_id);

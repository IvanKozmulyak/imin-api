-- V154__segments_origin_and_experiment_fks.sql
-- segments.origin marks the static arm segments the audience plan tool creates ('audience_plan'); the Audience tab
-- lists only 'organizer' rows. Also the foreign keys V135/V136 deferred. H2/PG-compatible: named constraints only.

ALTER TABLE segments ADD COLUMN origin VARCHAR(32) NOT NULL DEFAULT 'organizer';

-- The holdout percentage an invitation was made with; null on rows written before it was recorded.
ALTER TABLE audience_experiments ADD COLUMN holdout_pct INT;

-- Rows the new foreign keys would reject are dropped first so the migration cannot fail on existing data.
-- A plan's segments cascade with it (V135); assignments cascade with their experiment (V136).
DELETE FROM audience_plans
 WHERE NOT EXISTS (SELECT 1 FROM organizations o WHERE o.id = audience_plans.org_id);

DELETE FROM audience_experiments
 WHERE NOT EXISTS (SELECT 1 FROM events ev WHERE ev.id = audience_experiments.event_id)
    OR (plan_id IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM audience_plans p WHERE p.id = audience_experiments.plan_id))
    OR (plan_segment_id IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM audience_plan_segments s WHERE s.id = audience_experiments.plan_segment_id));

-- Plans belong to an org like the event they plan; an org delete already removes them through events.
ALTER TABLE audience_plans
    ADD CONSTRAINT fk_audience_plans_org FOREIGN KEY (org_id) REFERENCES organizations (id) ON DELETE CASCADE;

-- Experiments go with their event (and so with an org delete, which cascades through events).
ALTER TABLE audience_experiments
    ADD CONSTRAINT fk_audience_experiments_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE;

-- An experiment is the record of who was held out, so a plan or plan segment it points to cannot be deleted on
-- its own (NO ACTION): pruning must skip plans with experiments. An event delete removes both in one statement.
ALTER TABLE audience_experiments
    ADD CONSTRAINT fk_audience_experiments_plan FOREIGN KEY (plan_id) REFERENCES audience_plans (id);
ALTER TABLE audience_experiments
    ADD CONSTRAINT fk_audience_experiments_plan_segment
        FOREIGN KEY (plan_segment_id) REFERENCES audience_plan_segments (id);

-- One experiment per arm of an invited plan segment.
CREATE UNIQUE INDEX ux_audience_experiments_segment_arm ON audience_experiments (plan_segment_id, arm);

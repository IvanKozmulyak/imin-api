-- "Schedule invitations when I publish": the plan segments to invite once a draft event is published.
-- One row per event, consumed on publish. H2/PG-compatible: TEXT for JSON, named constraints only.

CREATE TABLE audience_plan_publish_invites (
    event_id   UUID PRIMARY KEY,
    org_id     UUID NOT NULL,
    segments   TEXT NOT NULL,
    created_by UUID,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_audience_plan_publish_invites_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE,
    CONSTRAINT fk_audience_plan_publish_invites_org FOREIGN KEY (org_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_audience_plan_publish_invites_user FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
);

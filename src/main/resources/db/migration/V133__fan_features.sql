-- V133__fan_features.sql
-- Per-membership features for the audience plan tool, derived from paid purchases only.
-- 1:1 with memberships; recomputed in place, never an audit trail. H2/PG-compatible:
-- JSON lives in TEXT, no native enum, no unnamed CHECK.

CREATE TABLE fan_features (
    membership_id               UUID         PRIMARY KEY
                                             REFERENCES memberships(membership_id) ON DELETE CASCADE,
    org_id                      UUID         NOT NULL,
    paid_orders                 INT          NOT NULL DEFAULT 0,
    first_paid_purchase_at      TIMESTAMP WITH TIME ZONE,
    last_paid_purchase_at       TIMESTAMP WITH TIME ZONE,
    -- loyal | repeat | first_timer | lapsing | dormant | imported | none
    class                       VARCHAR(16)  NOT NULL DEFAULT 'none',
    -- JSON object: genre bucket key -> weight (sums to 1, or empty)
    taste                       TEXT,
    -- JSON arrays, purchase-derived
    cities                      TEXT,
    formats                     TEXT,
    no_show_n                   INT          NOT NULL DEFAULT 0,
    avg_group_size              NUMERIC(6,3),
    sends_30d                   INT          NOT NULL DEFAULT 0,
    last_contact_from_person_at TIMESTAMP WITH TIME ZONE,
    logic_version               INT          NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX ix_fan_features_org_class ON fan_features (org_id, class);

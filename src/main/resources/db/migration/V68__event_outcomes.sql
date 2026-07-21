-- V68: Event outcome record — the AI Success Predictor's data foundation (spec §6.1).
-- One row per event, written in TWO passes:
--   1. Frozen at publish  — the pre-event snapshot of what the predictor knew when it
--      could still influence the setup (attributes, tiers, promos, provenance, organizer
--      tenure). Written inside EventService.publish() by EventOutcomeService.freezeOnPublish.
--   2. Finalized post-event — the actual result (sold, revenue, attendance, funnel, refunds)
--      filled by EventOutcomeFinalizeJob once the event has ended + a settlement grace.
--
-- This record formally REPLACES the Phase 1 "training-set logging" requirement (spec §6.1,
-- §10): the 2026-07-20 verification (86cav47ar) confirmed no such logging existed, so this
-- is greenfield.
--
-- NOTE: event_id is intentionally NOT a DB foreign key to events(id) — same app-scoped
-- pattern as V58 momentum_suggestions / V41 (newer append-style tables omit the FK so
-- repository tests can seed rows with random UUIDs without a real Event, and so a hard-
-- deleted event never blocks an insert). The freeze/finalize/backfill code only ever
-- writes for a real event it just read.
--
-- HONESTY COLUMNS (spec's no-fabrication rule): several fields the spec asks for have no
-- source in the current data model and are recorded as NULL, never invented:
--   * venue_type          — events carry no venue-type classification.
--   * indoor_open_air      — not derivable from any stored attribute.
--   * concept_ai_generated / poster_ai_generated — there is NO link from a published event
--                            back to a GeneratedEvent / PosterGeneration (the promote path
--                            stamps none), so AI provenance cannot be derived. NULL = unknown.
--   * nps                  — the post-event survey does not exist yet.
-- attendance_source records the imperfection instead of lying: 'scans' when door-scan
-- redemption data exists, 'sales' when we fall back to tickets-sold (Door PWA coverage is
-- partial — spec §6.1).

CREATE TABLE event_outcomes (
    -- ---- identity ----
    event_id                UUID PRIMARY KEY,
    org_id                  UUID NOT NULL,

    -- ---- frozen at publish: attributes ----
    city                    VARCHAR(255),
    country                 VARCHAR(2),
    venue_type              VARCHAR(64),              -- NULL: not in data model
    genre_family            VARCHAR(64),              -- events.genre (fixed 8-bucket taxonomy, V32)
    indoor_open_air         VARCHAR(16),              -- NULL: not derivable
    capacity                INT,                      -- sum of tier quantities at publish
    event_date              TIMESTAMP WITH TIME ZONE, -- events.starts_at
    day_of_week             SMALLINT,                 -- ISO 1=Mon .. 7=Sun, in the event's tz
    season                  VARCHAR(8),               -- WINTER|SPRING|SUMMER|AUTUMN (derived from event_date)
    capacity_band           VARCHAR(8),               -- LE100|B101_300|B301_800|GT800
    lead_time_days          INT,                      -- publish -> event, whole days

    -- ---- frozen at publish: structure & provenance ----
    tier_structure_json     TEXT NOT NULL DEFAULT '[]',  -- [{name,priceMinor,quantity,saleStartsAt,saleClosesAt}]
    promo_config_json       TEXT NOT NULL DEFAULT '[]',  -- [{code,discountPct,maxUses}]
    concept_ai_generated    BOOLEAN,                  -- NULL: no schema linkage (see header)
    poster_ai_generated     BOOLEAN,                  -- NULL: no schema linkage (see header)
    organizer_tenure_days   INT,                      -- org.created_at -> publish, whole days
    prior_event_count       INT,                      -- org's prior published events before this one

    snapshot_reconstructed  BOOLEAN NOT NULL DEFAULT false, -- true when reconstructed by the retro-backfill
    frozen_at               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    -- ---- finalized post-event (all nullable until the finalize job runs) ----
    sold_total              INT,
    sold_per_tier_json      TEXT,                     -- [{tierId,tierName,sold}]
    gross_revenue_minor     BIGINT,
    sell_out                BOOLEAN,
    time_to_sell_out_hours  INT,                      -- publish -> last seat sold, only when sold out
    refund_count            INT,
    refund_rate             NUMERIC(6,4),             -- refunded / issued, 0..1
    attendance              INT,
    attendance_source       VARCHAR(8),               -- 'scans' | 'sales'
    funnel_views            INT,                      -- distinct PAGE_VIEW sessions
    funnel_checkout_starts  INT,                      -- distinct CHECKOUT_START sessions
    funnel_paid             INT,                      -- orders (completed payments)
    campaign_sends          INT,                      -- campaign recipients sent during the sales window
    nps                     NUMERIC(6,3),             -- NULL: survey does not exist yet
    finalized_at            TIMESTAMP WITH TIME ZONE
);

-- Corpus retrieval segments on (genre_family, capacity_band, season) then relaxes
-- geography (city -> country) and drops season; index the hot paths.
CREATE INDEX ix_event_outcomes_segment   ON event_outcomes (genre_family, capacity_band, season);
CREATE INDEX ix_event_outcomes_city      ON event_outcomes (city);
CREATE INDEX ix_event_outcomes_country   ON event_outcomes (country);
CREATE INDEX ix_event_outcomes_org       ON event_outcomes (org_id);
CREATE INDEX ix_event_outcomes_finalized ON event_outcomes (finalized_at);

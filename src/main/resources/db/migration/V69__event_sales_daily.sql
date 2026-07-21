-- V69: Sales trajectory materialization (predictor spec §6.2).
-- Daily cumulative tickets sold, per tier per event, aggregated from the SOLD ticket set
-- (state NOT IN ('refunded','revoked')) — the SAME predicate as TicketRepository.tierAggregates,
-- so the trajectory reconciles with the per-tier sold figures rather than telling a second story.
--
-- Materialized by SalesTrajectoryJob (daily, ShedLock) with a full historical backfill on its
-- first run — every order ever taken is already a trajectory point, so no history is lost even
-- though the predictor ships later. NO new write path in checkout: this is a scheduled read-side
-- aggregation only. The normalized form (% of final sales vs days-to-event) that feeds the Stage 1
-- pacing curves is a SERVICE/repository query (SalesTrajectoryService.normalizedCurve), not a
-- second table.
--
-- Day bucketing is done IN JAVA in the event's timezone, never a SQL date(...) — H2 (test) and
-- Postgres diverge on truncation and this repo has been bitten by exactly that before.
--
-- event_id / tier_id carry no DB foreign key (same app-scoped pattern as V58/V68): the job only
-- ever writes for a real event it just read, and the recompute is delete+reinsert per event.

CREATE TABLE event_sales_daily (
    id              UUID PRIMARY KEY,
    event_id        UUID NOT NULL,
    tier_id         UUID NOT NULL,
    sales_date      DATE NOT NULL,   -- calendar day in the event's timezone
    daily_sold      INT  NOT NULL,   -- SOLD tickets whose created_at falls on this day
    cumulative_sold INT  NOT NULL,   -- running total for this tier up to and including this day
    CONSTRAINT uq_event_sales_daily UNIQUE (event_id, tier_id, sales_date)
);

CREATE INDEX ix_event_sales_daily_event ON event_sales_daily (event_id, sales_date);

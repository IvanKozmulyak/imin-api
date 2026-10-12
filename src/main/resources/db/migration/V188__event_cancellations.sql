-- Event cancellation: one row per cancelled event, one per order it refunds in full.
-- EventCancellationSweeper drives each order to a terminal state; the refund row is the money marker.

CREATE TABLE event_cancellations (
    event_id             UUID PRIMARY KEY REFERENCES events(id) ON DELETE CASCADE,
    org_id               UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    cancelled_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    cancelled_at         TIMESTAMPTZ NOT NULL
);

CREATE TABLE event_cancellation_orders (
    order_id          UUID PRIMARY KEY REFERENCES orders(id) ON DELETE CASCADE,
    event_id          UUID NOT NULL REFERENCES event_cancellations(event_id) ON DELETE CASCADE,
    state             VARCHAR(24) NOT NULL CHECK (state IN ('pending','in_progress','triggered','refunded',
                          'waiting_dispute','dispute_lost','nothing_to_refund','other_mode','needs_attention')),
    attempts          INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_refund_id    UUID REFERENCES refunds(id) ON DELETE SET NULL,
    last_error        VARCHAR(500),
    claimed_until     TIMESTAMPTZ,
    notify_claimed_at TIMESTAMPTZ,
    notify_attempts   INT NOT NULL DEFAULT 0,
    notify_outcome    VARCHAR(16) CHECK (notify_outcome IN ('sent','failed','no_address')),
    notified_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_event_cancellation_orders_event ON event_cancellation_orders (event_id);
CREATE INDEX ix_event_cancellation_orders_due ON event_cancellation_orders (claimed_until)
    WHERE state IN ('pending','in_progress','triggered','waiting_dispute','needs_attention');
CREATE INDEX ix_event_cancellation_orders_refund ON event_cancellation_orders (last_refund_id);
CREATE INDEX ix_event_cancellations_org ON event_cancellations (org_id);
CREATE INDEX ix_event_cancellations_user ON event_cancellations (cancelled_by_user_id);

-- Full-entity saves of an event hold no event lock (tier, promo, media writers), so a stale LIVE or
-- DRAFT could overwrite CANCELLED: always coerced back. An event cancelled through the app (it has an
-- event_cancellations row) stays CANCELLED whatever is written; one set CANCELLED by hand may still go PAST.
CREATE FUNCTION events_keep_cancelled() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'CANCELLED' AND NEW.status <> 'CANCELLED'
       AND (NEW.status IN ('DRAFT', 'LIVE')
            OR EXISTS (SELECT 1 FROM event_cancellations c WHERE c.event_id = OLD.id)) THEN
        NEW.status := 'CANCELLED';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER events_cancelled_is_terminal BEFORE UPDATE OF status ON events
    FOR EACH ROW EXECUTE FUNCTION events_keep_cancelled();

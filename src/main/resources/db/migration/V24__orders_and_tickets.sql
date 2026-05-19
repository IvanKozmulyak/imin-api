-- Order + Ticket persistence for the free-checkout flow.
--
-- Today the paid Stripe flow creates Stripe Sessions and reaches buyers via
-- Stripe-sent confirmation; nothing is persisted on our side beyond inventory
-- counts. Free orders skip Stripe entirely, so they need their own home.
-- This is the minimum schema needed for free-flow: a paid-flow wiring will
-- come in a follow-up that also writes here on `checkout.session.completed`.
--
-- Tokens are URL-safe random base64 strings (caller-generated, not derived
-- from id) so order/ticket lookup URLs cannot be enumerated.

CREATE TABLE orders (
    id                  UUID PRIMARY KEY,
    token               VARCHAR(64) NOT NULL UNIQUE,
    event_id            UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    org_id              UUID NOT NULL,
    email               VARCHAR(254) NOT NULL,
    total_minor         BIGINT NOT NULL,
    currency            VARCHAR(8) NOT NULL,
    promo_code_id       UUID,
    payment_method      VARCHAR(16) NOT NULL,
    stripe_session_id   VARCHAR(128),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_orders_event_id ON orders(event_id);
CREATE INDEX idx_orders_org_id ON orders(org_id);

-- One row per attendee. tier_id is intentionally NOT a foreign key — if an
-- organizer deletes a tier post-issue we still want the ticket to exist; the
-- snapshot of tier_name keeps the display string usable forever.
CREATE TABLE tickets (
    id              UUID PRIMARY KEY,
    token           VARCHAR(64) NOT NULL UNIQUE,
    order_id        UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    event_id        UUID NOT NULL,
    tier_id         UUID NOT NULL,
    tier_name       VARCHAR(128) NOT NULL,
    state           VARCHAR(16) NOT NULL DEFAULT 'pre',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tickets_order_id ON tickets(order_id);
CREATE INDEX idx_tickets_event_id ON tickets(event_id);

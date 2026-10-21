-- One reminder email to a buyer who ticked the organizer's marketing box at checkout and let the
-- Stripe session expire unpaid. A row exists only for a proven tick; the address and the ticked
-- sentence are wiped on the retention schedule while the status counts stay.
CREATE TABLE checkout_reminders (
    id                    UUID PRIMARY KEY,
    org_id                UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    event_id              UUID         NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    -- No FK: a deleted tier makes the send-time check answer SALES_CLOSED instead of losing the row.
    tier_id               UUID         NOT NULL,
    qty                   INT          NOT NULL CHECK (qty > 0),
    stripe_session_id     VARCHAR(255) NOT NULL UNIQUE,
    email_normalized      VARCHAR(254),
    buyer_locale          VARCHAR(5),
    promo_used            BOOLEAN      NOT NULL DEFAULT FALSE,
    consent_proof         VARCHAR(500),
    consent_text_version  VARCHAR(32),
    started_at            TIMESTAMPTZ  NOT NULL,
    captured_at           TIMESTAMPTZ  NOT NULL,
    due_at                TIMESTAMPTZ  NOT NULL,
    status                VARCHAR(16)  NOT NULL
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'NOT_SENT')),
    reason                VARCHAR(32),
    attempts              INT          NOT NULL DEFAULT 0,
    claimed_at            TIMESTAMPTZ,
    sent_at               TIMESTAMPTZ,
    provider_message_id   VARCHAR(128),
    delivered_at          TIMESTAMPTZ,
    unsubscribed_at       TIMESTAMPTZ,
    complained_at         TIMESTAMPTZ,
    recovered_order_id    UUID REFERENCES orders (id) ON DELETE SET NULL,
    recovered_at          TIMESTAMPTZ,
    address_wiped_at      TIMESTAMPTZ
);

-- One row per person per night: a second expiry updates the waiting row instead of adding one.
CREATE UNIQUE INDEX ux_checkout_reminders_event_email
    ON checkout_reminders (event_id, email_normalized) WHERE email_normalized IS NOT NULL;
CREATE UNIQUE INDEX ux_checkout_reminders_provider_message
    ON checkout_reminders (provider_message_id) WHERE provider_message_id IS NOT NULL;
CREATE INDEX ix_checkout_reminders_status_due ON checkout_reminders (status, due_at);
CREATE INDEX ix_checkout_reminders_org_captured ON checkout_reminders (org_id, captured_at);

-- The per-org switch lives off organizations so a full Organization save cannot revert it.
CREATE TABLE org_checkout_reminder_settings (
    org_id      UUID        PRIMARY KEY REFERENCES organizations (id) ON DELETE CASCADE,
    enabled     BOOLEAN     NOT NULL DEFAULT FALSE,
    enabled_at  TIMESTAMPTZ,
    enabled_by  UUID REFERENCES users (id) ON DELETE SET NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

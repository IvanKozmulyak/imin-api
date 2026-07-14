-- Meta CAPI outbox (spec §5) + ads-consent flag on orders (spec §7).

-- Cookie-consent-derived flag captured at order-paid. DEFAULT false so any order
-- without recorded ads consent never emits a server event.
ALTER TABLE orders ADD COLUMN ads_consent BOOLEAN NOT NULL DEFAULT false;

-- Outbox: one row per order-paid CAPI "Purchase" event to mirror to Meta.
-- Written in the SAME transaction as order issuance iff ads_consent AND a pixel
-- connection exists. Drained by MetaCapiPoller (@Scheduled 30s).
CREATE TABLE meta_capi_events (
    id               UUID         PRIMARY KEY,
    org_id           UUID         NOT NULL,
    order_id         UUID         NOT NULL,
    -- The order's public token (orders.token, VARCHAR(64) UNIQUE). This is the ONLY
    -- stable order identifier exposed to the buyer site (PublicOrderResponse never
    -- leaks DB UUIDs — verified), so it is the shared browser<->CAPI dedup key.
    order_token      VARCHAR(64)  NOT NULL,
    pixel_id         VARCHAR(32)  NOT NULL,
    -- event_id sent to Meta = the order TOKEN (browser<->CAPI dedup, spec §5). The
    -- browser Purchase pixel passes the same order.token as eventID.
    event_name       VARCHAR(32)  NOT NULL DEFAULT 'Purchase',
    -- sha256 hex of the normalized (lower+trim) buyer email — hashed PII only.
    email_sha256     VARCHAR(64)  NOT NULL,
    value_minor      BIGINT       NOT NULL,
    -- ISO 4217 code from orders.currency, never defaulted (builder rejects null/blank).
    currency         VARCHAR(8)   NOT NULL,
    fbp              VARCHAR(128)  NULL,
    fbc              VARCHAR(256)  NULL,
    event_time       BIGINT       NOT NULL,     -- unix seconds (Meta event_time)
    -- status: pending | sent | dead
    status           VARCHAR(16)  NOT NULL DEFAULT 'pending',
    attempts         SMALLINT     NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_error       TEXT         NULL,
    sent_at          TIMESTAMP WITH TIME ZONE NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- One CAPI event per order (dedup; idempotent same-TX insert).
ALTER TABLE meta_capi_events
    ADD CONSTRAINT uq_meta_capi_order UNIQUE (order_id);

-- Poller claim predicate: status='pending' AND next_attempt_at <= now().
CREATE INDEX ix_meta_capi_due ON meta_capi_events (status, next_attempt_at);
CREATE INDEX ix_meta_capi_org_created ON meta_capi_events (org_id, created_at DESC);

-- V31__refund_request_tokens.sql
-- Magic-link tokens for the buyer-facing refund-request form. Tokens are
-- 32 random bytes, Base64URL-encoded, stored as SHA-256 hex — we never
-- persist the raw token. Single-use: `consumed_at` is set on first POST.

CREATE TABLE refund_request_tokens (
  id                UUID PRIMARY KEY,
  token_hash        CHAR(64) NOT NULL UNIQUE,
  order_id          UUID NOT NULL REFERENCES orders(id),
  email_normalized  VARCHAR(254) NOT NULL,
  expires_at        TIMESTAMP WITH TIME ZONE NOT NULL,
  consumed_at       TIMESTAMP WITH TIME ZONE,
  created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_refund_request_tokens_expires_at
  ON refund_request_tokens (expires_at);

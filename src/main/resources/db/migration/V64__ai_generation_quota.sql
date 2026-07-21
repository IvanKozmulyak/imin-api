-- V64: per-user daily quota on AI poster generation (anti-abuse only).
--
-- Layered ON TOP of the existing burst RateLimiter (security/RateLimiter.java, the
-- ai-concept 10/60min bucket) — this is the slower rolling-24h ceiling that stops one
-- account from grinding the paid Ideogram image pipeline all day. It is invisible to a
-- normal organizer; only abusive volume trips it. Only the image pipeline
-- (POST /ai/events/concept + /concept/regenerate) is metered — text LLM calls are not.
--
-- ai_unlimited lives on the ORGANIZATION (not the user): an org-level escape hatch.
-- TRUE => every user in the org is never counted, never blocked. Grant with
--   UPDATE organizations SET ai_unlimited = TRUE WHERE slug = 'some-org';
ALTER TABLE organizations ADD COLUMN ai_unlimited BOOLEAN NOT NULL DEFAULT FALSE;

-- One row per ATTEMPT (recorded before the provider call — abuse is measured in attempts,
-- not successes). kind is 'image' today (future-proofed for other metered lanes).
-- AiQuotaService counts rows in the rolling last-24h window per (user_id, kind).
CREATE TABLE ai_generation_usage (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL,
    org_id     UUID        NOT NULL,
    kind       VARCHAR(16) NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT now()
);

-- Drives both the rolling-window COUNT and the oldest-row-in-window lookup (resetAt).
CREATE INDEX ix_ai_generation_usage_user_created ON ai_generation_usage (user_id, created_at);

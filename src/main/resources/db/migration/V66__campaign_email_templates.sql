-- V66: Branded, reusable campaign email templates (organizer Marketing tab).
-- A campaign renders its markdown body inside a template shell chosen via
-- campaigns.template_key. The four builtins (classic|midnight|poster|mono) are
-- code-defined (BuiltinTemplates) and are NOT rows here; this table holds only
-- org-saved templates (AI-generated today, hand-customized later).

CREATE TABLE campaign_email_templates (
    id          UUID         PRIMARY KEY,
    org_id      UUID         NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    -- Validated TemplateTokens as JSON. TEXT (not jsonb) for H2/PG parity: every
    -- prior JSON column in this schema (V33 stripe lists, V38 brand colours, V51
    -- segment rules, V52 exclusion_summary) is TEXT with a manual converter, because
    -- H2's PG-compat jsonb shim re-encodes on read and breaks Jackson. Colours are
    -- validated hex-only server-side (TemplateTokenValidator) before insert, so the
    -- DB does not need to enforce JSON validity.
    tokens      TEXT         NOT NULL,
    source      VARCHAR(16)  NOT NULL,                       -- 'ai' | 'custom'
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_campaign_email_templates_org ON campaign_email_templates (org_id, created_at DESC);

-- Which template a campaign renders with: a builtin key ('classic','midnight',
-- 'poster','mono') or a saved template's UUID (as text). DEFAULT 'classic' backfills
-- every existing row, so legacy campaigns render in the light brand-adjacent shell
-- rather than the old bare sans-serif fragment.
ALTER TABLE campaigns ADD COLUMN template_key VARCHAR(64) NOT NULL DEFAULT 'classic';

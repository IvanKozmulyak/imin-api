-- V145: AI Act Art.50(2) provenance for campaign copy (ADR-0005): sticky AI flags per part,
-- plus SHA-256 fingerprints of model-written copy offered for a campaign.

ALTER TABLE campaigns ADD COLUMN subject_ai_generated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE campaigns ADD COLUMN body_ai_generated BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE campaign_ai_suggestions (
  campaign_id  UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
  -- 'subject' | 'body' (body covers the preheader too)
  part         VARCHAR(16) NOT NULL,
  text_sha256  VARCHAR(64) NOT NULL,
  created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  CONSTRAINT pk_campaign_ai_suggestions PRIMARY KEY (campaign_id, part, text_sha256)
);

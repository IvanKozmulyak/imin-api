-- Org-level marketing send pause (spec §7 complaint-rate circuit breaker).
-- When set, the CampaignDispatcher refuses to send any of the org's campaigns
-- until an operator clears it. NULL = not paused.
ALTER TABLE organizations ADD COLUMN marketing_paused_at TIMESTAMP WITH TIME ZONE;

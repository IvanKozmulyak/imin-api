-- Foreign key columns whose parent rows are deleted in normal operation (consumer/DSAR erase,
-- membership/tier/campaign/plan/event/org/buyer-account delete) had no index led by the FK column,
-- so each parent delete seq-scanned the child table. Plain CREATE INDEX: the tables are small.
CREATE INDEX IF NOT EXISTS ix_memberships_consumer ON memberships (consumer_id);
CREATE INDEX IF NOT EXISTS ix_suppression_entries_membership ON suppression_entries (membership_id);
CREATE INDEX IF NOT EXISTS ix_ticket_reservations_tier ON ticket_reservations (tier_id);
CREATE INDEX IF NOT EXISTS ix_audience_experiments_campaign ON audience_experiments (campaign_id);
CREATE INDEX IF NOT EXISTS ix_audience_experiments_plan ON audience_experiments (plan_id);
CREATE INDEX IF NOT EXISTS ix_audience_experiments_event ON audience_experiments (event_id);
CREATE INDEX IF NOT EXISTS ix_audience_plans_event ON audience_plans (event_id);
CREATE INDEX IF NOT EXISTS ix_audience_plans_superseded_by ON audience_plans (superseded_by);
CREATE INDEX IF NOT EXISTS ix_audience_plan_publish_invites_org ON audience_plan_publish_invites (org_id);
CREATE INDEX IF NOT EXISTS ix_buyer_saved_events_event ON buyer_saved_events (event_id);
CREATE INDEX IF NOT EXISTS ix_meta_pixel_connections_event ON meta_pixel_connections (event_id);
CREATE INDEX IF NOT EXISTS ix_buyer_email_verification_codes_account ON buyer_email_verification_codes (buyer_account_id);
CREATE INDEX IF NOT EXISTS ix_buyer_password_reset_tokens_account ON buyer_password_reset_tokens (buyer_account_id);
CREATE INDEX IF NOT EXISTS ix_buyer_push_devices_account ON buyer_push_devices (buyer_account_id);

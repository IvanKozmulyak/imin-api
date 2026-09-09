-- mkt-core-6: the per-member frequency floor (CampaignVolumeGuard.isFrequencyCapped ->
-- CampaignRecipientRepository.countRecentSendsForMembership) filters on
-- (membership_id, last_event_at), and V53 created only (campaign_id, status) and
-- (provider_message_id). The UNIQUE(campaign_id, membership_id) constraint leads with
-- campaign_id, so no index served a membership_id-only predicate and every candidate in a
-- materialised segment cost one sequential scan of a table that grows with every send.
CREATE INDEX ix_campaign_recipients_membership
    ON campaign_recipients (membership_id, last_event_at);

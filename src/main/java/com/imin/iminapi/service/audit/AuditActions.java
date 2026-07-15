package com.imin.iminapi.service.audit;

/**
 * Canonical action strings stored in the {@code audit_logs.action} column.
 * Kept as constants (not an enum) so the frontend can extend the vocabulary
 * over time without forcing a migration when a new action is introduced.
 */
public final class AuditActions {
    private AuditActions() {}

    public static final String EVENT_CREATED = "EVENT_CREATED";
    public static final String EVENT_UPDATED = "EVENT_UPDATED";
    public static final String EVENT_PUBLISHED = "EVENT_PUBLISHED";
    public static final String EVENT_UNPUBLISHED = "EVENT_UNPUBLISHED";
    public static final String TIER_CREATED = "TIER_CREATED";
    public static final String TIER_UPDATED = "TIER_UPDATED";
    public static final String TIER_DELETED = "TIER_DELETED";
    public static final String PROMO_CREATED = "PROMO_CREATED";
    public static final String PROMO_UPDATED = "PROMO_UPDATED";
    public static final String PROMO_DELETED = "PROMO_DELETED";
    public static final String MEMBER_INVITED = "MEMBER_INVITED";
    public static final String MEMBER_REMOVED = "MEMBER_REMOVED";
    public static final String STRIPE_ONBOARDED = "STRIPE_ONBOARDED";

    // ---- Audience / Consent / DSAR (Tier C) ----
    public static final String AUDIENCE_TAG_ADDED       = "AUDIENCE_TAG_ADDED";
    public static final String SEGMENT_CREATED          = "SEGMENT_CREATED";
    public static final String SEGMENT_DELETED          = "SEGMENT_DELETED";
    public static final String SEGMENT_SNAPSHOT         = "SEGMENT_SNAPSHOT";
    public static final String CONSENT_CAPTURED         = "CONSENT_CAPTURED";
    public static final String CONSENT_UNSUBSCRIBED     = "CONSENT_UNSUBSCRIBED";
    public static final String SUPPRESSION_ADDED        = "SUPPRESSION_ADDED";
    public static final String AUDIENCE_HANDOFF         = "AUDIENCE_HANDOFF";
    public static final String DSAR_ACCESS              = "DSAR_ACCESS";
    public static final String DSAR_EXPORT              = "DSAR_EXPORT";
    public static final String DSAR_RECTIFY             = "DSAR_RECTIFY";
    public static final String DSAR_OBJECT              = "DSAR_OBJECT";
    public static final String DSAR_ERASE_REQUESTED     = "DSAR_ERASE_REQUESTED";
    public static final String DSAR_ERASE_EXECUTED      = "DSAR_ERASE_EXECUTED";

    // ---- Marketing campaigns (Phase 1) ----
    public static final String CAMPAIGN_CREATED = "CAMPAIGN_CREATED";
    public static final String CAMPAIGN_DUPLICATED = "CAMPAIGN_DUPLICATED";
    public static final String CAMPAIGN_TEST_SENT = "CAMPAIGN_TEST_SENT";
    public static final String CAMPAIGN_DELETED = "CAMPAIGN_DELETED";
}

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
}

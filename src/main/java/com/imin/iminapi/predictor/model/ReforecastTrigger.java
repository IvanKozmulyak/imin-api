package com.imin.iminapi.predictor.model;

/**
 * What caused a live re-forecast recompute (spec §4.2 / §7.3, task 86cav479j). Recorded for
 * observability; the recompute itself is idempotent regardless of trigger.
 */
public enum ReforecastTrigger {
    /** Daily 06:00 Europe/Amsterdam cron over live, future, on-sale events. */
    SCHEDULED,
    /** A 25/50/75% event-level sold crossing on the fulfilment path. */
    MILESTONE,
    /** A ticket-tier transition activated. */
    TIER_TRANSITION,
    /** A marketing campaign send completed. */
    CAMPAIGN_SEND,
    /** Organizer hit the throttled manual refresh. */
    MANUAL
}

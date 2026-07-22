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
    /** A ticket-tier transition activated (a tier reached 100% sell-through / closed). */
    TIER_TRANSITION,
    /** A marketing campaign send completed. */
    CAMPAIGN_SEND,
    /** A marketing campaign was scheduled to send. */
    CAMPAIGN_SCHEDULED,
    /** The organizer executed a prescriptive recommendation (feedback type=executed) — closes the loop. */
    ACTION_EXECUTED,
    /** An organizer edited a live event (price/date/capacity/tier/promo). */
    EDIT,
    /** Baseline re-forecast fired the moment a previously-scored draft was published. */
    PUBLISH,
    /** Organizer hit the throttled manual refresh. */
    MANUAL
}

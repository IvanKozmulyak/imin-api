package com.imin.iminapi.marketing.model;

/** Campaign recipient status sets shared by native SQL and JPQL; constants so they fit in {@code @Query}. */
public final class RecipientStatuses {

    private RecipientStatuses() {}

    /** The email left the provider for this person; bounced, failed, skipped and pending did not. */
    public static final String SENT_SQL = "('sent', 'delivered', 'opened', 'clicked', 'complained', 'unsubscribed')";
}

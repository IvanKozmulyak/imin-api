package com.imin.iminapi.marketing.model;

import java.util.List;

/**
 * Campaign recipient status sets shared by native SQL and JPQL; constants so they fit in {@code @Query}.
 *
 * <p>One definition of each campaign number, read by the campaign page, the list and the Channels card:
 * <ul>
 *   <li>sent = {@link #LEFT_THE_QUEUE}: the email left for the provider, bounced included.</li>
 *   <li>delivered = {@link #REACHED_INBOX_SQL}: accepted by the recipient's mail provider, including people who later unsubscribed or
 *       complained; a bounce is never delivered, even when its delivered webhook arrives late.</li>
 *   <li>bounced / failed / skipped = rows in that status; failed rows never left.</li>
 *   <li>queued = pending rows.</li>
 * </ul>
 */
public final class RecipientStatuses {

    private RecipientStatuses() {}

    /** The email left the provider for this person; bounced, failed, skipped and pending did not. */
    public static final String SENT_SQL = "('sent', 'delivered', 'opened', 'clicked', 'complained', 'unsubscribed')";

    /** Every status a row can hold once its email actually left, bounced included. */
    public static final List<String> LEFT_THE_QUEUE = List.of(
            "sent", "delivered", "opened", "clicked", "bounced", "complained", "unsubscribed");

    /** {@link #LEFT_THE_QUEUE} as a SQL list. */
    public static final String LEFT_THE_QUEUE_SQL =
            "('sent', 'delivered', 'opened', 'clicked', 'bounced', 'complained', 'unsubscribed')";

    /** Rows the recipient's mail provider accepted: delivered or beyond, but never bounced. */
    public static final String REACHED_INBOX_SQL = "('delivered', 'opened', 'clicked', 'unsubscribed', 'complained')";
}

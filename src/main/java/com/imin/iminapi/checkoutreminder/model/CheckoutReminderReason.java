package com.imin.iminapi.checkoutreminder.model;

/** Why a reminder ended NOT_SENT. Each value is a check the sender ran at send time, or a send outcome. */
public enum CheckoutReminderReason {
    /** An order for the night from this address exists. */
    HAS_TICKETS,
    /** The tier is still on sale but no ticket is left. */
    SOLD_OUT,
    /** The night or tier is no longer on sale: closed, cancelled, unpublished, deleted or under a sales hold. */
    SALES_CLOSED,
    /** The night starts sooner than the minimum lead time. */
    TOO_CLOSE_TO_DOORS,
    /** The checkout started longer ago than the maximum age. */
    TOO_LATE,
    /** Opted out of this organizer, unsubscribed or suppressed in their audience. */
    UNSUBSCRIBED,
    /** The address bounced permanently or was reported undeliverable. */
    UNDELIVERABLE,
    /** The address was erased, platform-wide or for this organizer. */
    ERASED,
    /** The version of the ticked sentence is no longer on the organizer-named allowlist. */
    CONSENT_VERSION_RETIRED,
    /** The organizer's marketing email is paused. */
    PAUSED,
    /** The organizer turned the reminder off. */
    DISABLED,
    /** The organizer no longer has the legal name and contact the footer needs. */
    LEGAL_IDENTITY_MISSING,
    /** The provider refused the email, or every retry failed. */
    SEND_FAILED,
    /** The sender stopped between the provider call and recording it; never retried. */
    SEND_UNKNOWN
}

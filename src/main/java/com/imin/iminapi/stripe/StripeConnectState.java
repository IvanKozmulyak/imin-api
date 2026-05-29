package com.imin.iminapi.stripe;

/**
 * The user-facing states for the organizer-side Stripe Connect onboarding banner.
 * Derived from the persisted mirror columns; see StripeConnectStatusMirror#derive.
 *
 * <p>{@code DISABLED} is terminal-and-unrecoverable from the organizer's side: the
 * recipient {@code stripe_transfers} capability is {@code unsupported} (unsupported
 * country / business / entity type) or its {@code status_details.resolution} is
 * {@code contact_stripe}. The organizer cannot self-serve out of it — the FE shows a
 * "contact support" message rather than a "continue onboarding" CTA.
 */
public enum StripeConnectState {
    NOT_STARTED,
    ONBOARDING,
    PENDING_VERIFICATION,
    RESTRICTED,
    DISABLED,
    ACTIVE
}

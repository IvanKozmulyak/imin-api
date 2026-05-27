package com.imin.iminapi.stripe;

/**
 * The 5 user-facing states for the organizer-side Stripe Connect onboarding banner.
 * Derived from the persisted mirror columns; see StripeConnectStatusMirror#derive.
 */
public enum StripeConnectState {
    NOT_STARTED,
    ONBOARDING,
    PENDING_VERIFICATION,
    RESTRICTED,
    ACTIVE
}

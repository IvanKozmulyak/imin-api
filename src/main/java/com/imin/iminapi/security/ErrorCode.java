package com.imin.iminapi.security;

public enum ErrorCode {
    FIELD_INVALID,
    INVALID_REQUEST,
    AUTH_MISSING,
    AUTH_INVALID_CREDENTIALS,
    AUTH_TOKEN_EXPIRED,
    EMAIL_NOT_VERIFIED,
    INVALID_CODE,
    INVALID_TOKEN,
    FORBIDDEN,
    ORG_PLAN_LIMIT,
    NOT_FOUND,
    STALE_WRITE,
    INVALID_STATE,
    DUPLICATE,
    PRICE_CHANGED,
    PUBLISH_VALIDATION_FAILED,
    STRIPE_NOT_READY,
    COUNTRY_NOT_ALLOWED,
    COUNTRY_NOT_SUPPORTED,
    RATE_LIMITED,
    OAUTH_PROVIDER_DISABLED,
    OAUTH_INVALID_STATE,
    OAUTH_EMAIL_REQUIRED,
    OAUTH_EMAIL_CONFLICT,
    /**
     * The provider returned an address it has not verified. Distinct from
     * {@link #OAUTH_EMAIL_CONFLICT}: nothing conflicts, the assertion is simply
     * not trustworthy enough to mint a verified address claim from (buyer
     * accounts epic §15 D-3).
     */
    OAUTH_EMAIL_UNVERIFIED,
    AI_QUOTA_EXCEEDED,
    INTERNAL,
    UPSTREAM_UNAVAILABLE,
    MISSING_IDEMPOTENCY_KEY,
    ORDER_NOT_REFUNDABLE,
    TICKET_ALREADY_REFUNDED,
    TICKET_REDEEMED,
    STRIPE_REFUND_FAILED,
    REFUND_TOKEN_EXPIRED_OR_CONSUMED,
    REFUND_REQUEST_ALREADY_OPEN,
    NO_REFUNDABLE_TICKETS,
    REFUND_REQUEST_NOT_PENDING,
    REFUND_APPROVAL_NOT_CONFIRMED,
    META_UPSTREAM_ERROR,

    /**
     * A DJ-photo upload arrived without {@code rightsAttested=true}. A third
     * party's face is about to enter an AI pipeline (Ideogram character
     * reference, OpenRouter vision gate) and nobody has claimed the right to
     * put it there — droit à l'image (C. civ. 9), CPI L122-4.
     */
    RIGHTS_ATTESTATION_REQUIRED,

    // ---- Audience CSV import ----
    IMPORT_ATTESTATION_REQUIRED,
    IMPORT_FILE_REQUIRED,
    IMPORT_FILE_TOO_LARGE,
    IMPORT_TOO_MANY_ROWS,
    IMPORT_EMAIL_COLUMN_MISSING,
    IMPORT_INVALID_CSV,

    /**
     * DELETE /api/v1/orgs refused: the organization holds records imin cannot
     * legally destroy on request — orders, tickets, settlements or payouts.
     * 409, and terminal from the API's side: there is no force flag.
     */
    ORG_HAS_RECORDS,

    // ---- Buyer accounts ----
    /**
     * Unlinking this identity would leave the account with no way to sign in —
     * no password and no other provider. Returned by
     * {@code DELETE /buyer/identities/{provider}} as a 409.
     */
    LAST_CREDENTIAL
}

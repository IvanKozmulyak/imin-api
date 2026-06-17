package com.imin.iminapi.controller.payout.dto;

/**
 * Response of {@code POST /api/v1/payouts/connect}. The rendered PayoutsTab does
 * NOT consume this shape (its connect CTA routes to /settings/payments and reads
 * org Stripe state from {@code GET /orgs/{orgId}/stripe/status}); {@code /payouts/connect}
 * is only pre-registered in the FE idempotency allowlist. We still return a small
 * body so a caller can follow the onboarding URL if desired.
 *
 * <pre>
 *   url      string  — the Stripe onboarding link the organizer's browser should open
 *   accountId string — the acct_... id (newly created or pre-existing)
 * </pre>
 */
public record PayoutConnectResponse(
        String url,
        String accountId
) {}

package com.imin.iminapi.controller.payout.dto;

/**
 * Response of {@code GET /api/v1/payouts/status}. Mirrors the FE
 * {@code PayoutsStatus} interface (imin-webapp src/shared/api/types.ts:202-208)
 * exactly:
 *
 * <pre>
 *   stripeConnected boolean  — account exists AND payouts enabled (mirror)
 *   accountLast4    string   — last 4 of the connected bank account (nullable → may be null)
 *   dashboardUrl    string   — single-use Express dashboard login link (nullable → may be null)
 * </pre>
 *
 * <p>{@code accountLast4} and {@code dashboardUrl} are honestly null when they
 * can't be obtained yet (no external account attached, onboarding incomplete,
 * or a Stripe read failure) — the service NEVER fabricates them. The FE guards
 * both ({@code status.data.accountLast4}, {@code status.data.dashboardUrl}).
 */
public record PayoutsStatusResponse(
        boolean stripeConnected,
        String accountLast4,
        String dashboardUrl
) {}

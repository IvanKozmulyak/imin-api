package com.imin.iminapi.controller.payout.dto;

/**
 * Response of {@code GET /api/v1/payouts/summary}. Mirrors the FE
 * {@code PayoutsSummary} interface (imin-webapp src/shared/api/types.ts:230-238)
 * exactly:
 *
 * <pre>
 *   inTransit       number   — sum(amount) of in_transit settlements (minor units)
 *   thisMonth       number   — sum(amount) of payouts PAID this calendar month (minor units)
 *   pending         number   — sum(amount) of pending settlements (minor units)
 *   arrivesOnLabel  string?  — optional; next expected arrival, null when unknown
 *   thisMonthCount  number?  — optional; count of payouts this month, null when n/a
 * </pre>
 *
 * <p>All money fields are minor-unit integers (the FE runs them through
 * {@code formatMoney}). The two optional fields are nullable so Jackson emits
 * {@code null} — the FE guards both ({@code arrivesOnLabel ?? ''},
 * {@code thisMonthCount ? … : ''}).
 */
public record PayoutsSummaryResponse(
        long inTransit,
        long thisMonth,
        long pending,
        String arrivesOnLabel,
        Integer thisMonthCount
) {}

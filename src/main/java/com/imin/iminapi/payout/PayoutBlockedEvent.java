package com.imin.iminapi.payout;

import java.util.UUID;

/**
 * Published when a {@link PayoutRun} is parked {@link PayoutRunStatus#BLOCKED} — either because
 * the connected account has no bank account, or because the attempt cap was reached. Published
 * only on the transition INTO {@code BLOCKED}, so the nightly sweep cannot re-notify. Consumed
 * by {@link OrganizerPayoutNotifier} after the payout transaction commits.
 */
public record PayoutBlockedEvent(UUID runId) {}

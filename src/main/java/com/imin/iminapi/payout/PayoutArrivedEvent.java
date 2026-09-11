package com.imin.iminapi.payout;

import java.util.UUID;

/**
 * Published when a {@link PayoutRun} settles ({@code payout.paid}) — on the transition into
 * {@code PAID}/{@code PARTIAL} only, so a webhook redelivery emails nobody twice. Consumed by
 * {@link OrganizerPayoutNotifier}, which honours the organizer's {@code payout_arrived}
 * notification preference.
 */
public record PayoutArrivedEvent(UUID runId) {}

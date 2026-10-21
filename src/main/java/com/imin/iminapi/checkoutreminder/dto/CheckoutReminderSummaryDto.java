package com.imin.iminapi.checkoutreminder.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The organizer's reminder card and results page. Counts cover reminders captured in the last
 * {@code windowDays}; {@code unpaidCheckouts} counts paid checkouts released unpaid in the same window.
 * {@code captured = sent + waiting + notSent}. No buyer address appears anywhere in this DTO.
 *
 * @param available        the platform switch ({@code imin.checkout-reminder.enabled})
 * @param quietStart       org-local "HH:mm" from which no reminder goes out, until {@code quietEnd}
 * @param canEdit          the caller is an owner or admin
 * @param salesMinor       net sales of orders bought after a reminder; null when they span currencies
 * @param notSentByReason  reason name to count, NOT_SENT rows only
 */
public record CheckoutReminderSummaryDto(
        boolean available,
        boolean enabled,
        Instant enabledAt,
        String enabledByName,
        boolean canEdit,
        boolean legalIdentityReady,
        String legalName,
        String legalContact,
        String senderName,
        boolean marketingPaused,
        String timezone,
        String quietStart,
        String quietEnd,
        int windowDays,
        int delayMinutes,
        int outcomeWindowDays,
        long unpaidCheckouts,
        long captured,
        long sent,
        long waiting,
        long notSent,
        Map<String, Long> notSentByReason,
        long boughtAfter,
        Long salesMinor,
        String currency,
        List<RecentRow> recent
) {
    /** One reminder, newest first; {@code boughtNetMinor} is null for a test-mode order or none. */
    public record RecentRow(UUID eventId, String eventName, String tierName, int qty, Instant startedAt,
                            String status, String reason, Instant dueAt, Instant sentAt, Instant boughtAt,
                            Long boughtNetMinor) {}
}

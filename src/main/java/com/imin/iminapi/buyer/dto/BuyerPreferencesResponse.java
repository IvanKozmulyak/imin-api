package com.imin.iminapi.buyer.dto;

import java.util.UUID;

/**
 * {@code GET|PATCH /buyer/preferences} (spec §4.4).
 *
 * <p>{@code ticketDelivery} is deliberately absent: delivering a ticket someone
 * paid for is performance of a contract, not marketing, and carries no opt-out.
 * The UI renders it as a locked row with no field behind it.
 *
 * <p>{@code productNews} is stored but has no sender — epic §6.4 cut "News from
 * IMIN" for want of a lawful basis. It is returned so the shape is honest about
 * the column, and no screen renders it.
 */
public record BuyerPreferencesResponse(
        boolean eventReminders,
        /** DERIVED from membership consent, never stored. See BuyerPreferencesService. */
        boolean organizerUpdates,
        /**
         * True when the buyer holds memberships and every one of them carries a
         * sticky {@code marketing_optouts} row. The master switch then has
         * nothing it may turn on, and the UI must say so rather than offering a
         * control that silently does nothing.
         */
        boolean organizerUpdatesLocked,
        boolean productNews) {

    /**
     * One row of {@code GET /buyer/organizers} — read-only disclosure of who
     * holds what. There is deliberately no per-organizer PATCH: re-subscribing
     * belongs on the organizer's own footer link, where the proof text is theirs.
     */
    public record Organizer(UUID orgId, String name, boolean subscribed, boolean optedOut) {}
}

package com.imin.iminapi.controller.payout.dto;

import com.imin.iminapi.settlement.Settlement;
import com.imin.iminapi.util.Times;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One row in the organizer dashboard Payouts tab, projected from a
 * {@link Settlement} read-model row. JSON field names + types mirror the FE
 * {@code PayoutRow} interface (imin-webapp src/shared/api/types.ts:184-200)
 * byte-for-byte so the api:check drift gate stays green:
 *
 * <pre>
 *   id            string   (UUID)
 *   orgId         string   (UUID)
 *   amountMinor   number   (long, minor units)
 *   currency      string
 *   status        string   — one of 'pending' | 'in_transit' | 'paid'
 *   eventIds      string[] (UUIDs)
 *   createdAt     string   (ISO instant)
 *   arrivesOn     string   (ISO-date, or "" when unknown)
 *   stripePayoutId string  (the tr_/po_ id == settlement.stripeObjectId)
 *   eventsLabel   string   (server-denormalized comma-joined event names)
 * </pre>
 *
 * <p>{@code status} is emitted as the snake_case wire literal
 * ({@code in_transit}, not {@code IN_TRANSIT}) via
 * {@link com.imin.iminapi.settlement.SettlementStatus#toWire()} — the FE Chip
 * switches on the literal {@code 'in_transit'}.
 */
public record PayoutRowResponse(
        UUID id,
        UUID orgId,
        long amountMinor,
        String currency,
        String status,
        List<UUID> eventIds,
        Instant createdAt,
        String arrivesOn,
        String stripePayoutId,
        String eventsLabel
) {

    private static final DateTimeFormatter ARRIVES_ON =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    /**
     * Project a settlement row. {@code eventNames} maps event UUID → display
     * name so {@code eventsLabel} can be denormalized without a per-row query;
     * pass {@link Map#of()} when names aren't resolved (label degrades to the
     * raw ids or empty string).
     */
    public static PayoutRowResponse from(Settlement s, Map<UUID, String> eventNames) {
        List<UUID> ids = parseEventIds(s.getEventIds());

        // arrivesOn: prefer the Stripe payout arrival_date; fall back to "" so
        // the FE (`payout.arrivesOn || ...`) renders nothing rather than "null".
        String arrivesOn = s.getArrivalAt() == null ? "" : ARRIVES_ON.format(s.getArrivalAt());

        String label = ids.stream()
                .map(id -> {
                    String name = eventNames.get(id);
                    return (name == null || name.isBlank()) ? null : name;
                })
                .filter(n -> n != null)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        return new PayoutRowResponse(
                s.getId(),
                s.getOrgId(),
                s.getAmountMinor(),
                s.getCurrency(),
                s.getStatus().toWire(),
                ids,
                s.getCreatedAt() == null ? Times.nowMicros() : s.getCreatedAt(),
                arrivesOn,
                s.getStripeObjectId(),
                label);
    }

    /** Split the nullable CSV of event UUIDs into a typed list (empty when null/blank). */
    private static List<UUID> parseEventIds(String csv) {
        List<UUID> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            try {
                out.add(UUID.fromString(t));
            } catch (IllegalArgumentException ignored) {
                // ponytail: tolerate a malformed id in the CSV rather than failing the whole row.
            }
        }
        return out;
    }
}

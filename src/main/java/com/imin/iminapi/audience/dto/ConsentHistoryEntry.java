package com.imin.iminapi.audience.dto;

import com.imin.iminapi.audience.model.ConsentRecord;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the append-only consent trail, as the outside world sees it.
 *
 * <p>{@code consent_records} has been written faithfully since Tier C —
 * {@code proof_text}, {@code lawful_basis} and {@code source} are all there —
 * but nothing read it back except a {@code COUNT(*)} for a metrics tile. A
 * consent record nobody can produce is not a proof: it cannot answer a DSAR,
 * a regulator, or an organizer disputing "I never signed up for this".
 *
 * <p>{@code granted} rather than the raw {@code status} string: the stored
 * vocabulary is {@code subscribed}/{@code unsubscribed}, and a boolean is the
 * thing a reader actually needs. The raw values stay in the table.
 *
 * <p>{@code proofText} is returned verbatim, so a door/survey proof still names the event by id;
 * display {@code eventName} rather than parsing it.
 */
public record ConsentHistoryEntry(
        Instant at,
        String channel,
        boolean granted,
        String lawfulBasis,
        String source,
        String proofText,
        // Version of the consent sentence shown and the order it was given on; null when absent.
        String textVersion,
        UUID orderId,
        // Buyer's checkout language on that order; null without an order or when none was recorded.
        String locale,
        // True for a granting record ConsentGate does not accept as proof (reason legacy_unproven).
        boolean legacy,
        // Door QR / survey records count only once the address is confirmed; confirmedAt null means still waiting.
        boolean confirmationRequired,
        Instant confirmedAt,
        // Event the consent was given at, and its name when the event belongs to the caller's org.
        UUID eventId,
        String eventName
) {
    public static ConsentHistoryEntry from(ConsentRecord r, String locale, boolean legacy, String eventName) {
        return new ConsentHistoryEntry(
                r.getOccurredAt(),
                r.getChannel(),
                "subscribed".equals(r.getStatus()),
                r.getLawfulBasis(),
                r.getSource(),
                r.getProofText(),
                r.getTextVersion(),
                r.getOrderId(),
                locale,
                legacy,
                r.isConfirmationRequired(),
                r.getConfirmedAt(),
                r.getEventId(),
                eventName);
    }
}

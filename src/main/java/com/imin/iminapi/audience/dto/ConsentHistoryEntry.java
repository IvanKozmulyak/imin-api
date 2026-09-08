package com.imin.iminapi.audience.dto;

import com.imin.iminapi.audience.model.ConsentRecord;

import java.time.Instant;

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
 */
public record ConsentHistoryEntry(
        Instant at,
        String channel,
        boolean granted,
        String lawfulBasis,
        String source,
        String proofText
) {
    public static ConsentHistoryEntry from(ConsentRecord r) {
        return new ConsentHistoryEntry(
                r.getOccurredAt(),
                r.getChannel(),
                "subscribed".equals(r.getStatus()),
                r.getLawfulBasis(),
                r.getSource(),
                r.getProofText());
    }
}

package com.imin.iminapi.marketing.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API view of a momentum suggestion (spec §6.4; FE type {@code MomentumSuggestionDto} in
 * imin-webapp marketing/types.ts). Built by {@code MomentumService.list} — there is no static
 * {@code from(entity)} factory any more, because the enriched fields need org-scoped context
 * (event names, segment names, the org's phone count) that an entity alone cannot supply.
 *
 * <p>Every field traces to real data. Nothing here is placeholder copy, and nothing is
 * synthesized from a scalar — see {@code spark}.
 *
 * <h2>Snapshotted vs resolved-live (deliberate, per field)</h2>
 * <b>Snapshotted</b> — the evidence for why this fired, frozen at evaluation in
 * {@code metricsSnapshot} and never rewritten, so the organizer sees the numbers the engine
 * actually acted on:
 * <ul>
 *   <li>{@code metricsSnapshot} — raw JSON passthrough, unchanged. Still the audit record.</li>
 *   <li>{@code spark} — lifted verbatim out of that snapshot (see below).</li>
 *   <li>{@code headline} / {@code pace} / {@code daysOutLabel} — derived on read by
 *       {@code MomentumProse}, but ONLY from the frozen snapshot. Pure function of a frozen
 *       input ⇒ identical to deriving at write, and it also backfills rows written before
 *       these fields existed. They restate the snapshot; they never re-measure it.</li>
 * </ul>
 * <b>Resolved live</b> — identity and capability, which are not evidence and SHOULD track
 * reality, so a rename or a new opt-in is reflected immediately:
 * <ul>
 *   <li>{@code eventName} — live from {@code events}. A renamed event must not leave the card
 *       naming an event that no longer exists under that name. Null when the event has been
 *       hard-deleted; the FE falls back to the id rather than being handed a stale string.</li>
 *   <li>{@code segmentLabel} — live name of {@code draftPayload.segmentId}. Null when the draft
 *       carries no segment or the segment has since been deleted — the label is genuinely
 *       unknown then, and a guess would be worse than an omission.</li>
 *   <li>{@code smsLocked} — live: the org has zero SMS-opted-in phones RIGHT NOW, so an SMS
 *       send is impossible. A capability, not evidence; it must unlock the moment a phone is
 *       collected.</li>
 * </ul>
 *
 * @param spark REAL tickets sold per UTC day over the recent window, oldest → newest, taken
 *              from the {@code tickets} table at evaluation and frozen into the snapshot.
 *              <b>{@code null} when the suggestion's snapshot has no series</b> (rows written
 *              before this shipped, or an event with no sold tickets in the window) — the FE
 *              must hide the chart, NOT rebuild a curve out of {@code sellThroughPct}. A series
 *              synthesized from one scalar can only draw the shape the synthesis implies, which
 *              is why the {@code slump} trigger could never render the decline it exists to
 *              signal. This field is the honest replacement; keep it honest.
 * @param headline short all-caps banner, e.g. {@code "LAST 113 TICKETS"}
 * @param pace one sentence restating the trend, e.g. {@code "Selling 5 tickets/day, down 46%
 *             on the previous 5 days — at this pace you finish around 74% of capacity"}
 * @param daysOutLabel contextual time prose, e.g. {@code "Doors in 64h"}, {@code "On-sale 2
 *                     days"}, {@code "Sold out 6 days early"}
 * @param smsLocked true ⇔ the org has 0 SMS-subscribed phones with a number on file
 *                  ({@code MembershipRepository.countSmsSubscribedByOrgId})
 */
public record MomentumSuggestionDto(
        UUID id,
        UUID eventId,
        String eventName,
        String triggerType,
        String status,
        String metricsSnapshot,
        String draftPayload,
        UUID campaignId,
        Instant suggestedAt,
        String headline,
        String pace,
        String daysOutLabel,
        List<Integer> spark,
        String segmentLabel,
        boolean smsLocked) {
}

package com.imin.iminapi.predictor.service;

import com.imin.iminapi.marketing.model.MomentumStatus;
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.predictor.dto.PredictionResult;
import com.imin.iminapi.predictor.dto.PredictionResult.ActionTarget;
import com.imin.iminapi.predictor.dto.PredictionResult.Recommendation;
import com.imin.iminapi.predictor.model.FeedbackType;
import com.imin.iminapi.predictor.model.PredictionFeedback;
import com.imin.iminapi.predictor.repository.PredictionFeedbackRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The recommendation engine (spec §4.3; tasks 86cav479w recommendation ranking, 86cav479z
 * structured action links, 86cav47a5 dismissal memory). It sits between the guardrail-validated
 * raw Stage-0 output and what a surface serves:
 *
 * <ol>
 *   <li><b>Normalize</b> ({@link #finalizeForRender}) — each raw {@link Stage0Scorer.RecCandidate}
 *       becomes a served {@link Recommendation}: {@code actionType} lower-cased to the closed set,
 *       the model's tier reference fuzzy-matched to a REAL {@code tierId} (never invented),
 *       price/date folded into a structured {@link ActionTarget}, and — for {@code campaign}
 *       actions — the id of the event's live momentum suggestion attached so the FE deep-links
 *       into the exact card. Then sorted impact-descending (HIGH before MED), deterministic
 *       tiebreak by id, and capped at ≤3. This is what gets LEDGERED.</li>
 *   <li><b>Dismissal filter</b> ({@link #applyDismissals}) — at serve time, drop any rendered
 *       recommendation whose {@link #fingerprint} matches an event feedback row whose latest
 *       state for that fingerprint is DISMISSED (RESTORED clears it). ≤3 is re-asserted AFTER
 *       filtering, and the count of suppressed recommendations is returned for the FE's
 *       "N dismissed — remembered" row.</li>
 * </ol>
 *
 * <p><b>Fingerprint</b> (dismissal identity, §4.3): {@code sha256(actionType | tierKey |
 * priceBucket)} where {@code tierKey} is the resolved {@code tierId} (or {@code none}) and
 * {@code priceBucket = floor(suggestedPriceMinor / 500)} — i.e. €5 buckets. So a dismissed
 * "lower Early Bird to €15" (bucket 3) still suppresses a re-surfaced "€15.50" (bucket 3) but
 * NOT "€12" (bucket 2), a different tier, or a different action type — those are materially
 * changed and return.
 */
@Service
public class RecommendationEngine {

    /** Price bucket width in minor units (€5). Same-bucket price nudges share a fingerprint. */
    static final int PRICE_BUCKET_MINOR = 500;

    static final int MAX_ACTIVE = 3;

    private final TicketTierRepository tiers;
    private final MomentumSuggestionRepository momentum;
    private final PredictionFeedbackRepository feedback;

    public RecommendationEngine(TicketTierRepository tiers, MomentumSuggestionRepository momentum,
                                PredictionFeedbackRepository feedback) {
        this.tiers = tiers;
        this.momentum = momentum;
        this.feedback = feedback;
    }

    // ---- 1. normalize raw candidates into the served, ledgered shape -----------

    /**
     * Turn guardrail-validated raw candidates into served recommendations: normalize action
     * type, resolve tier ids, fold price/date + momentum deep-link into a structured target,
     * order impact-descending (id tiebreak), cap ≤3.
     */
    public List<Recommendation> finalizeForRender(UUID eventId, List<Stage0Scorer.RecCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();

        List<TicketTier> eventTiers = tiers.findByEventIdOrderBySortOrderAsc(eventId);
        UUID liveMomentumId = liveMomentumSuggestionId(eventId);

        List<Recommendation> out = new ArrayList<>();
        for (Stage0Scorer.RecCandidate c : candidates) {
            if (c == null) continue;
            String actionType = c.actionType() == null ? null : c.actionType().toLowerCase(Locale.ROOT);
            String impact = c.impact() == null ? null : c.impact().toUpperCase(Locale.ROOT);
            UUID tierId = resolveTierId(c.tierRef(), eventTiers);
            UUID momentumId = "campaign".equals(actionType) ? liveMomentumId : null;
            ActionTarget target = buildTarget(tierId, c.priceMinor(), c.dateIso(), momentumId);
            out.add(new Recommendation(c.id(), c.claim(), c.evidence(), impact, actionType, target));
        }

        out.sort(Comparator
                .comparingInt((Recommendation r) -> impactRank(r.impact()))
                .thenComparing(r -> r.id() == null ? "" : r.id()));
        return out.size() > MAX_ACTIVE ? new ArrayList<>(out.subList(0, MAX_ACTIVE)) : out;
    }

    /** null when every structured field is absent — the FE then falls back to the surface generally. */
    private static ActionTarget buildTarget(UUID tierId, Integer priceMinor, String dateIso, UUID momentumId) {
        if (tierId == null && priceMinor == null && (dateIso == null || dateIso.isBlank()) && momentumId == null) {
            return null;
        }
        return new ActionTarget(tierId, priceMinor, (dateIso == null || dateIso.isBlank()) ? null : dateIso, momentumId);
    }

    private static int impactRank(String impact) {
        return "HIGH".equalsIgnoreCase(impact) ? 0 : 1; // HIGH first; MED/unknown after
    }

    /**
     * Fuzzy-match the model's tier reference to a real event tier. Case-insensitive: exact name
     * first, then containment either direction. Null when nothing matches — a tier id is NEVER
     * invented (§4.3 / task 86cav479z).
     */
    private static UUID resolveTierId(String tierRef, List<TicketTier> eventTiers) {
        if (tierRef == null || tierRef.isBlank() || eventTiers.isEmpty()) return null;
        String ref = tierRef.trim().toLowerCase(Locale.ROOT);
        for (TicketTier t : eventTiers) {
            if (t.getName() != null && t.getName().trim().toLowerCase(Locale.ROOT).equals(ref)) return t.getId();
        }
        for (TicketTier t : eventTiers) {
            String name = t.getName() == null ? "" : t.getName().trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && (name.contains(ref) || ref.contains(name))) return t.getId();
        }
        return null;
    }

    /** The id of the event's single LIVE momentum suggestion (any trigger), or null. */
    private UUID liveMomentumSuggestionId(UUID eventId) {
        List<MomentumSuggestion> live = momentum.findByEventIdAndStatus(eventId, MomentumStatus.SUGGESTED.wireValue());
        return live.isEmpty() ? null : live.get(0).getId();
    }

    // ---- 2. serve-time dismissal filter ----------------------------------------

    /** A filtered recommendation list plus how many were suppressed by dismissal memory. */
    public record Filtered(List<Recommendation> recommendations, int dismissedCount) {}

    /**
     * Drop recommendations dismissed for this event (unless materially changed / restored),
     * re-assert ≤3, and report the suppressed count. A prediction with no recommendations is a
     * no-op returning an empty list and count 0.
     */
    public Filtered applyDismissals(UUID eventId, List<Recommendation> recs) {
        if (recs == null || recs.isEmpty()) return new Filtered(List.of(), 0);

        Map<String, FeedbackType> latestByFingerprint = latestFeedbackByFingerprint(eventId);
        List<Recommendation> kept = new ArrayList<>();
        int dismissed = 0;
        for (Recommendation r : recs) {
            String fp = fingerprint(r);
            if (latestByFingerprint.get(fp) == FeedbackType.DISMISSED) {
                dismissed++;
            } else {
                kept.add(r);
            }
        }
        if (kept.size() > MAX_ACTIVE) kept = new ArrayList<>(kept.subList(0, MAX_ACTIVE));
        return new Filtered(kept, dismissed);
    }

    /** Latest DISMISSED/RESTORED state per fingerprint for an event (executed rows carry no fingerprint). */
    private Map<String, FeedbackType> latestFeedbackByFingerprint(UUID eventId) {
        java.util.Map<String, PredictionFeedback> latest = new java.util.HashMap<>();
        for (PredictionFeedback fb : feedback.findByEventId(eventId)) {
            String fp = fb.getFingerprint();
            if (fp == null || fp.isBlank()) continue; // only dismissed/restored carry a fingerprint
            PredictionFeedback cur = latest.get(fp);
            if (cur == null || fb.getCreatedAt().isAfter(cur.getCreatedAt())) latest.put(fp, fb);
        }
        java.util.Map<String, FeedbackType> out = new java.util.HashMap<>();
        latest.forEach((fp, fb) -> out.put(fp, fb.getFeedbackType()));
        return out;
    }

    // ---- fingerprint (dismissal identity) --------------------------------------

    /**
     * The dismissal fingerprint of a served recommendation (§4.3). Stable across cosmetic claim
     * rewrites and same-€5-bucket price nudges; distinct across action type, tier, or a
     * different price bucket. Computed on write of a dismissal and matched at serve time.
     */
    public static String fingerprint(Recommendation r) {
        String actionType = r.actionType() == null ? "" : r.actionType().toLowerCase(Locale.ROOT);
        ActionTarget t = r.actionTarget();
        String tierKey = (t != null && t.tierId() != null) ? t.tierId().toString() : "none";
        Integer price = t == null ? null : t.suggestedPriceMinor();
        String priceBucket = price == null ? "none" : Long.toString(Math.floorDiv((long) price, PRICE_BUCKET_MINOR));
        return sha256(actionType + "|" + tierKey + "|" + priceBucket);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}

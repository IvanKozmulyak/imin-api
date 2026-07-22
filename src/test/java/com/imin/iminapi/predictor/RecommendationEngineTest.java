package com.imin.iminapi.predictor;

import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.predictor.dto.PredictionResult.ActionTarget;
import com.imin.iminapi.predictor.dto.PredictionResult.Recommendation;
import com.imin.iminapi.predictor.model.FeedbackType;
import com.imin.iminapi.predictor.model.PredictionFeedback;
import com.imin.iminapi.predictor.repository.PredictionFeedbackRepository;
import com.imin.iminapi.predictor.service.RecommendationEngine;
import com.imin.iminapi.predictor.service.Stage0Scorer.RecCandidate;
import com.imin.iminapi.repository.TicketTierRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The recommendation engine (tasks 86cav479w impact ranking, 86cav479z structured links,
 * 86cav47a5 dismissal memory): impact-descending ordering with id tiebreak, ≤3 cap, tier-id
 * resolution, momentum deep-link, fingerprint stability, and the same-bucket / changed-bucket /
 * restore dismissal behaviour.
 */
class RecommendationEngineTest {

    private final TicketTierRepository tiers = mock(TicketTierRepository.class);
    private final MomentumSuggestionRepository momentum = mock(MomentumSuggestionRepository.class);
    private final PredictionFeedbackRepository feedback = mock(PredictionFeedbackRepository.class);
    private final RecommendationEngine sut = new RecommendationEngine(tiers, momentum, feedback);

    private final UUID eventId = UUID.randomUUID();

    private TicketTier tier(UUID id, String name) {
        TicketTier t = new TicketTier();
        t.setId(id);
        t.setEventId(eventId);
        t.setName(name);
        return t;
    }

    private RecCandidate cand(String id, String impact, String actionType, String tierRef, Integer price) {
        return new RecCandidate(id, "claim " + id, "evidence " + id, impact, actionType, tierRef, price, null);
    }

    // ---- 1. normalize: ordering, cap, tier resolution, momentum ----------------

    @Test
    void ordersImpactDescendingWithIdTiebreakAndCapsAtThree() {
        when(tiers.findByEventIdOrderBySortOrderAsc(eventId)).thenReturn(List.of());
        when(momentum.findByEventIdAndStatus(eventId, "suggested")).thenReturn(List.of());

        List<Recommendation> out = sut.finalizeForRender(eventId, List.of(
                cand("z-med", "MED", "campaign", null, null),
                cand("b-high", "HIGH", "campaign", null, null),
                cand("a-high", "HIGH", "campaign", null, null),
                cand("y-med", "MED", "campaign", null, null))); // 4 in → capped to 3

        assertThat(out).hasSize(3);
        assertThat(out).extracting(Recommendation::id).containsExactly("a-high", "b-high", "y-med");
        assertThat(out).extracting(Recommendation::impact).containsExactly("HIGH", "HIGH", "MED");
    }

    @Test
    void resolvesTierIdByFuzzyNameAndNeverInvents() {
        UUID earlyId = UUID.randomUUID();
        when(tiers.findByEventIdOrderBySortOrderAsc(eventId))
                .thenReturn(List.of(tier(earlyId, "Early Bird"), tier(UUID.randomUUID(), "Door")));
        when(momentum.findByEventIdAndStatus(eventId, "suggested")).thenReturn(List.of());

        List<Recommendation> out = sut.finalizeForRender(eventId, List.of(
                cand("r1", "HIGH", "tier_edit", "early bird", 1400),   // fuzzy → Early Bird
                cand("r2", "MED", "tier_edit", "VIP Lounge", 3000)));   // no match → null

        assertThat(out.get(0).actionTarget().tierId()).isEqualTo(earlyId);
        assertThat(out.get(0).actionTarget().suggestedPriceMinor()).isEqualTo(1400);
        assertThat(out.get(1).actionTarget().tierId()).isNull(); // never invented
    }

    @Test
    void attachesLiveMomentumSuggestionOnlyToCampaignActions() {
        UUID momentumId = UUID.randomUUID();
        MomentumSuggestion ms = mock(MomentumSuggestion.class);
        when(ms.getId()).thenReturn(momentumId);
        when(tiers.findByEventIdOrderBySortOrderAsc(eventId)).thenReturn(List.of());
        when(momentum.findByEventIdAndStatus(eventId, "suggested")).thenReturn(List.of(ms));

        List<Recommendation> out = sut.finalizeForRender(eventId, List.of(
                cand("promote", "HIGH", "campaign", null, null),
                cand("announce", "MED", "announce", null, null)));

        assertThat(out.get(0).actionTarget().momentumSuggestionId()).isEqualTo(momentumId);
        // non-campaign action with no tier/price/date → null target (FE falls back to the surface)
        assertThat(out.get(1).actionTarget()).isNull();
    }

    // ---- 2. fingerprint stability ----------------------------------------------

    @Test
    void fingerprintCollidesInSameEuroBucketButNotAcross() {
        UUID t = UUID.randomUUID();
        String fp15 = RecommendationEngine.fingerprint(rec("tier_edit", t, 1500));
        String fp1550 = RecommendationEngine.fingerprint(rec("tier_edit", t, 1550)); // same €5 bucket
        String fp12 = RecommendationEngine.fingerprint(rec("tier_edit", t, 1200));   // different bucket
        String fpOtherType = RecommendationEngine.fingerprint(rec("capacity", t, 1500));

        assertThat(fp15).isEqualTo(fp1550);
        assertThat(fp15).isNotEqualTo(fp12);
        assertThat(fp15).isNotEqualTo(fpOtherType);
    }

    // ---- 3. dismissal filter ---------------------------------------------------

    @Test
    void dismissedSameBucketSuppressedChangedBucketReturns() {
        UUID t = UUID.randomUUID();
        Recommendation dismissed15 = rec("tier_edit", t, 1500);
        when(feedback.findByEventId(eventId)).thenReturn(List.of(
                fb(RecommendationEngine.fingerprint(dismissed15), FeedbackType.DISMISSED, 0)));

        // Re-surfaced at €15.50 (same bucket) → suppressed; a €12 variant (different bucket) → returns.
        RecommendationEngine.Filtered f = sut.applyDismissals(eventId,
                List.of(rec("tier_edit", t, 1550), rec("tier_edit", t, 1200)));

        assertThat(f.dismissedCount()).isEqualTo(1);
        assertThat(f.recommendations()).extracting(r -> r.actionTarget().suggestedPriceMinor())
                .containsExactly(1200);
    }

    @Test
    void restoreClearsAPriorDismissal() {
        UUID t = UUID.randomUUID();
        Recommendation r = rec("tier_edit", t, 1500);
        String fp = RecommendationEngine.fingerprint(r);
        // dismissed first, then restored LATER → latest state is RESTORED → not suppressed.
        when(feedback.findByEventId(eventId)).thenReturn(List.of(
                fb(fp, FeedbackType.DISMISSED, 0),
                fb(fp, FeedbackType.RESTORED, 10)));

        RecommendationEngine.Filtered f = sut.applyDismissals(eventId, List.of(r));

        assertThat(f.dismissedCount()).isZero();
        assertThat(f.recommendations()).hasSize(1);
    }

    // ---- helpers ---------------------------------------------------------------

    private static Recommendation rec(String actionType, UUID tierId, Integer priceMinor) {
        return new Recommendation("rid", "claim", "evidence", "HIGH", actionType,
                new ActionTarget(tierId, priceMinor, null, null));
    }

    private static PredictionFeedback fb(String fingerprint, FeedbackType type, int secondsOffset) {
        PredictionFeedback f = new PredictionFeedback();
        f.setEventId(UUID.randomUUID());
        f.setLedgerId(UUID.randomUUID());
        f.setRecommendationId("rid");
        f.setFeedbackType(type);
        f.setFingerprint(fingerprint);
        f.setCreatedAt(Instant.parse("2026-06-01T12:00:00Z").plusSeconds(secondsOffset));
        return f;
    }
}

package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.model.CapacityBand;
import com.imin.iminapi.predictor.model.RelaxationLevel;
import com.imin.iminapi.predictor.model.Season;
import com.imin.iminapi.predictor.rules.Finding.Status;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.service.ComparableCorpusService;
import com.imin.iminapi.predictor.service.ComparableCorpusService.ComparableCorpus;
import com.imin.iminapi.predictor.service.ComparableCorpusService.ForeignAggregate;
import com.imin.iminapi.predictor.service.ComparableCorpusService.OwnEvent;
import com.imin.iminapi.predictor.service.CompetingNightsService;
import com.imin.iminapi.predictor.service.PacingCurveService;
import com.imin.iminapi.predictor.service.PacingCurveService.CurveMatch;
import com.imin.iminapi.predictor.service.PacingEngine.Curve;
import com.imin.iminapi.predictor.service.PacingEngine.CurvePoint;
import com.imin.iminapi.repository.EventRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.imin.iminapi.predictor.rules.RuleFixtures.in;
import static com.imin.iminapi.predictor.rules.RuleFixtures.q;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Comparable sell-out (2.9) and pacing (10.2) over stubbed corpus and curve answers. */
class InternalEvaluatorComparablesTest {

    /** Saturday 14 Nov 2026; Paris is UTC+1. */
    private static final LocalDate D = LocalDate.of(2026, 11, 14);

    private final ComparableCorpusService corpus = mock(ComparableCorpusService.class);
    private final PacingCurveService curves = mock(PacingCurveService.class);
    private final InternalEvaluator evaluator = new InternalEvaluator(mock(CompetingNightsService.class),
            mock(EventRepository.class), corpus, curves);
    private final UUID ownOrg = UUID.randomUUID();

    private Finding eval(String id, DateCheckInput in) {
        return evaluator.evaluate(q(id, SourceKind.INTERNAL), in, D);
    }

    // --- 2.9 ---

    private void corpusReturns(int own, int ownSellOuts, ForeignAggregate foreign) {
        List<OwnEvent> ownEvents = new ArrayList<>();
        for (int i = 0; i < own; i++) {
            ownEvents.add(new OwnEvent(UUID.randomUUID(), "Own " + i, 200, 100_000L, i < ownSellOuts, 200, 200,
                    Instant.parse("2025-11-14T22:00:00Z")));
        }
        int foreignCount = foreign == null ? 0 : foreign.count();
        when(corpus.retrieve(any(), any(), any(), any(), any(), any())).thenReturn(new ComparableCorpus(
                RelaxationLevel.NONE, own + foreignCount, own, foreignCount, ownEvents, foreign));
    }

    private static ForeignAggregate foreign(int count, double rate) {
        return new ForeignAggregate(count, 200, 200, 100_000L, rate);
    }

    @Test
    void comparablesSellOutOpportunity() {
        corpusReturns(2, 1, foreign(5, 0.4));

        Finding f = eval("2.9", in().org(ownOrg).build());

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.kind()).isEqualTo(Kind.OPPORTUNITY);
        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.facts()).containsEntry("sellOutRate", 0.45).containsEntry("n", 7)
                .containsEntry("relaxation", "NONE");
        verify(corpus).retrieve(ownOrg, "paris", "FR", "house & techno", CapacityBand.B101_300,
                Season.AUTUMN);
    }

    @Test
    void lowSellOutRisk() {
        corpusReturns(0, 0, foreign(10, 0.0));

        Finding f = eval("2.9", in().org(ownOrg).build());

        assertThat(f.kind()).isEqualTo(Kind.RISK);
        assertThat(f.strength()).isEqualTo(2);
    }

    @Test
    void middleClear() {
        corpusReturns(0, 0, foreign(10, 0.2));

        assertThat(eval("2.9", in().org(ownOrg).build()).status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void nullCapacityNotChecked() {
        Finding f = eval("2.9", in().org(ownOrg).capacity(null).build());

        assertThat(f.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "not_provided");
        assertThat(eval("10.2", in().org(ownOrg).capacity(null).build()).facts()).containsEntry("reason", "not_provided");
        verifyNoInteractions(corpus, curves);
    }

    @Test
    void underMinEventsNotChecked() {
        corpusReturns(4, 4, null); // foreign cluster suppressed for privacy: only own events count

        Finding f = eval("2.9", in().org(ownOrg).build());

        assertThat(f.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "no_data");
    }

    // --- 10.2 ---

    private void curveReturns(CurvePoint... points) {
        when(curves.lookup(any(), any(), any(), any(), any())).thenReturn(
                Optional.of(new CurveMatch(RelaxationLevel.NONE, new Curve(12, List.of(points)))));
    }

    @Test
    void noCurveNotChecked() {
        when(curves.lookup(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        Finding f = eval("10.2", in().org(ownOrg).build());

        assertThat(f.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "no_data");
    }

    @Test
    void shortLeadMissesBuyersRisk() {
        // lead is 45 days: the first point at or beyond it is daysOut 50
        curveReturns(new CurvePoint(60, 0.2, 0.1, 0.3), new CurvePoint(50, 0.3, 0.2, 0.4),
                new CurvePoint(30, 0.6, 0.5, 0.7));

        Finding f = eval("10.2", in().org(ownOrg).build());

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.facts()).containsEntry("leadDays", 45L).containsEntry("soldShareBefore", 0.3);
        verify(curves).lookup("paris", "FR", "house & techno", CapacityBand.B101_300, Season.AUTUMN);

        curveReturns(new CurvePoint(50, 0.55, 0.4, 0.6));
        assertThat(eval("10.2", in().org(ownOrg).build()).strength()).isEqualTo(3);
    }

    @Test
    void longLeadClear() {
        curveReturns(new CurvePoint(40, 0.3, 0.2, 0.4));
        assertThat(eval("10.2", in().org(ownOrg).build()).status()).isEqualTo(Status.CLEAR); // no point as far out as 45 days

        curveReturns(new CurvePoint(50, 0.2, 0.1, 0.3));
        assertThat(eval("10.2", in().org(ownOrg).build()).status()).isEqualTo(Status.CLEAR);
    }
}

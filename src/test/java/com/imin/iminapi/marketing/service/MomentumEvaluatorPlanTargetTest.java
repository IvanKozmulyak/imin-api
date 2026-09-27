package com.imin.iminapi.marketing.service;

import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.audienceplan.service.MomentumPlanTarget;
import com.imin.iminapi.marketing.dto.MomentumDraftPayload;
import com.imin.iminapi.marketing.model.MomentumSuggestion;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Momentum's target choice between the audience plan's best segment and the prebuilt Repeat, and the refresh hook. */
class MomentumEvaluatorPlanTargetTest {

    static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    final EventRepository events = mock(EventRepository.class);
    final OrderRepository orders = mock(OrderRepository.class);
    final TicketTierRepository tiers = mock(TicketTierRepository.class);
    final TicketRepository tickets = mock(TicketRepository.class);
    final MomentumSuggestionRepository suggestions = mock(MomentumSuggestionRepository.class);
    final MomentumCopyGenerator copy = mock(MomentumCopyGenerator.class);
    final SendGateService sendGate = mock(SendGateService.class);
    final SegmentService segments = mock(SegmentService.class);
    final MomentumNotifier notifier = mock(MomentumNotifier.class);
    final MomentumPlanTarget planTarget = mock(MomentumPlanTarget.class);
    final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    final MomentumEvaluator evaluator = new MomentumEvaluator(events, orders, tiers, tickets, suggestions,
            new MomentumThresholds(), copy, sendGate, segments, notifier, planTarget, publisher);

    UUID orgId;
    UUID repeatId;
    UUID snapshotId;
    Event event;
    List<UUID> repeatMembers;
    List<UUID> planMembers;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        repeatId = UUID.randomUUID();
        snapshotId = UUID.randomUUID();
        event = new Event();
        event.setId(UUID.randomUUID());
        event.setOrgId(orgId);
        event.setName("Night Kit");
        // 5 of 100 sold, on sale 50 h: launch push fires.
        event.setOnSaleAt(NOW.minus(Duration.ofHours(50)));
        event.setStartsAt(NOW.plus(Duration.ofDays(30)));
        when(tiers.sumSoldByEventId(event.getId())).thenReturn(5);
        when(tiers.sumQuantityByEventId(event.getId())).thenReturn(100);
        when(orders.findCreatedAtAndTotalSince(eq(event.getId()), any())).thenReturn(List.of());
        when(tickets.findSoldCreatedAtSince(eq(event.getId()), any())).thenReturn(List.of());
        when(suggestions.findByEventIdAndTriggerTypeAndStatus(any(), any(), any())).thenReturn(Optional.empty());
        when(suggestions.findTopByEventIdAndTriggerTypeOrderBySuggestedAtDesc(any(), any())).thenReturn(Optional.empty());

        repeatMembers = ids(15);
        planMembers = ids(12);
        when(segments.defaultTargetSegmentId(orgId)).thenReturn(repeatId);
        when(segments.resolveMembershipIds(orgId, repeatId)).thenReturn(repeatMembers);
        when(sendGate.evaluate(any(), any())).thenAnswer(inv ->
                new SendGateService.GateResult(inv.getArgument(1), List.of()));
        when(planTarget.best(event)).thenReturn(Optional.empty());
        when(planTarget.snapshot(eq(orgId), eq("Night Kit"), any())).thenReturn(snapshotId);
        when(copy.generate(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new MomentumDraftPayload("s", "p", "b",
                        ((UUID) inv.getArgument(6)).toString(), null, "why"));
    }

    @Test
    void planTarget_draftTargetsTheSnapshotOfThePlanMembers() {
        MomentumPlanTarget.Target t = new MomentumPlanTarget.Target("loyal", "same", planMembers);
        when(planTarget.best(event)).thenReturn(Optional.of(t));

        evaluator.evaluateOne(event, NOW);

        verify(sendGate).evaluate(orgId, planMembers);
        verify(planTarget).snapshot(orgId, "Night Kit", t);
        verify(copy).generate(any(), any(), any(), any(), any(), any(), eq(snapshotId));
        assertThat(saved().getDraftPayload()).contains("\"segmentId\":\"" + snapshotId + "\"");
        verify(segments, never()).defaultTargetSegmentId(any());
        verify(planTarget, never()).discard(any(), any());
        verify(notifier).notifyOwner(orgId, "launch_push", "why");
    }

    @Test
    void noPlanTarget_keepsRepeat() {
        evaluator.evaluateOne(event, NOW);

        verify(sendGate).evaluate(orgId, repeatMembers);
        verify(planTarget, never()).snapshot(any(), any(), any());
        assertThat(saved().getDraftPayload()).contains("\"segmentId\":\"" + repeatId + "\"");
    }

    @Test
    void planTargetBelowTheFloor_fallsBackToRepeat_withoutASnapshot() {
        MomentumPlanTarget.Target t = new MomentumPlanTarget.Target("loyal", "same", planMembers);
        when(planTarget.best(event)).thenReturn(Optional.of(t));
        when(sendGate.evaluate(orgId, planMembers))
                .thenReturn(new SendGateService.GateResult(planMembers.subList(0, 9), List.of()));

        evaluator.evaluateOne(event, NOW);

        verify(planTarget, never()).snapshot(any(), any(), any());
        assertThat(saved().getDraftPayload()).contains("\"segmentId\":\"" + repeatId + "\"");
    }

    @Test
    void planLookupFailure_fallsBackToRepeat() {
        when(planTarget.best(event)).thenThrow(new IllegalStateException("plan read failed"));

        evaluator.evaluateOne(event, NOW);

        assertThat(saved().getDraftPayload()).contains("\"segmentId\":\"" + repeatId + "\"");
    }

    @Test
    void snapshotFailure_fallsBackToRepeat_withNothingToDiscard() {
        MomentumPlanTarget.Target t = new MomentumPlanTarget.Target("loyal", "same", planMembers);
        when(planTarget.best(event)).thenReturn(Optional.of(t));
        when(planTarget.snapshot(orgId, "Night Kit", t)).thenThrow(new IllegalStateException("segment write failed"));

        evaluator.evaluateOne(event, NOW);

        verify(sendGate).evaluate(orgId, repeatMembers);
        verify(copy).generate(any(), any(), any(), any(), any(), any(), eq(repeatId));
        assertThat(saved().getDraftPayload()).contains("\"segmentId\":\"" + repeatId + "\"");
        verify(planTarget, never()).discard(any(), any());
        verify(notifier).notifyOwner(orgId, "launch_push", "why");
    }

    @Test
    void repeatBelowTheFloor_stillSkips() {
        when(segments.resolveMembershipIds(orgId, repeatId)).thenReturn(repeatMembers.subList(0, 9));

        evaluator.evaluateOne(event, NOW);

        verify(suggestions, never()).save(any());
        verify(copy, never()).generate(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void suggestionWriteFails_snapshotIsDiscarded_andTheErrorSurfaces() {
        when(planTarget.best(event)).thenReturn(Optional.of(new MomentumPlanTarget.Target("loyal", "same", planMembers)));
        IllegalStateException boom = new IllegalStateException("save failed");
        when(suggestions.save(any())).thenThrow(boom);

        assertThatThrownBy(() -> evaluator.evaluateOne(event, NOW)).isSameAs(boom);

        verify(planTarget).discard(orgId, snapshotId);
        verify(notifier, never()).notifyOwner(any(), any(), any());
    }

    @Test
    void failingDiscard_isSuppressed_andTheOriginalErrorSurfaces() {
        when(planTarget.best(event)).thenReturn(Optional.of(new MomentumPlanTarget.Target("loyal", "same", planMembers)));
        IllegalStateException boom = new IllegalStateException("copy failed");
        doThrow(boom).when(copy).generate(any(), any(), any(), any(), any(), any(), any());
        IllegalStateException cleanup = new IllegalStateException("delete failed");
        doThrow(cleanup).when(planTarget).discard(orgId, snapshotId);

        assertThatThrownBy(() -> evaluator.evaluateOne(event, NOW)).isSameAs(boom);
        assertThat(boom.getSuppressed()).containsExactly(cleanup);
    }

    @Test
    void repeatTargetWriteFails_nothingToDiscard() {
        when(suggestions.save(any())).thenThrow(new IllegalStateException("save failed"));

        assertThatThrownBy(() -> evaluator.evaluateOne(event, NOW)).hasMessage("save failed");

        verify(planTarget, never()).discard(any(), any());
    }

    @Test
    void firedTrigger_publishesAPlanRefreshRequest() {
        evaluator.evaluateOne(event, NOW);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(published.capture());
        assertThat(published.getValue()).isEqualTo(new MomentumTriggered(orgId, event.getId(), "launch_push"));
    }

    @Test
    void publishFailure_neverStopsTheSuggestion() {
        doThrow(new IllegalStateException("queue full")).when(publisher).publishEvent(any(Object.class));

        evaluator.evaluateOne(event, NOW);

        verify(suggestions).save(any());
    }

    @Test
    void noTrigger_publishesNothing() {
        when(tiers.sumSoldByEventId(event.getId())).thenReturn(50); // 50 % sold, 30 days out: no rule fires

        evaluator.evaluateOne(event, NOW);

        verify(publisher, never()).publishEvent(any(Object.class));
        verify(planTarget, never()).best(any());
    }

    @Test
    void liveSuggestionGuardrail_publishesNothing() {
        when(suggestions.findByEventIdAndTriggerTypeAndStatus(event.getId(), "launch_push", "suggested"))
                .thenReturn(Optional.of(new MomentumSuggestion()));

        evaluator.evaluateOne(event, NOW);

        verify(publisher, never()).publishEvent(any(Object.class));
        verify(planTarget, never()).best(any());
    }

    private MomentumSuggestion saved() {
        ArgumentCaptor<MomentumSuggestion> s = ArgumentCaptor.forClass(MomentumSuggestion.class);
        verify(suggestions).save(s.capture());
        assertThat(s.getValue().getStatus()).isEqualTo("suggested");
        return s.getValue();
    }

    private static List<UUID> ids(int n) {
        return IntStream.range(0, n).mapToObj(i -> UUID.randomUUID()).toList();
    }
}

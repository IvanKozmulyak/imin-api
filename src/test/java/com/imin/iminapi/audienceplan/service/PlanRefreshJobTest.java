package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.service.PlanService.Refresh;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.predictor.service.PredictorReactivityEvents;
import com.imin.iminapi.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanRefreshJobTest {

    private static final Instant NOW = Instant.parse("2026-09-27T07:00:00Z");

    private final PlanService plans = mock(PlanService.class);
    private final EventRepository events = mock(EventRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InviteOnPublishService invites = mock(InviteOnPublishService.class);
    private final PlanPruner pruner = mock(PlanPruner.class);

    @Test
    void run_countsEachOutcome_andKeepsGoingPastAFailingEvent() {
        Event created = event();
        Event unchanged = event();
        Event failing = event();
        Event skipped = event();
        when(events.findMomentumCandidates(NOW)).thenReturn(List.of(created, unchanged, failing, skipped));
        when(plans.refresh(created.getId())).thenReturn(Refresh.CREATED);
        when(plans.refresh(unchanged.getId())).thenReturn(Refresh.UNCHANGED);
        when(plans.refresh(failing.getId())).thenThrow(new IllegalStateException("boom"));
        when(plans.refresh(skipped.getId())).thenReturn(Refresh.SKIPPED);

        PlanRefreshJob.Result r = new PlanRefreshJob(plans, events, clock, null, invites, pruner).run();

        assertThat(r).isEqualTo(new PlanRefreshJob.Result(4, 1, 1, 1, 1, 0));
        verify(plans).refresh(skipped.getId());
    }

    @Test
    void run_prunesAfterTheRefreshes_andCountsThePrunedPlans() {
        Event e = event();
        when(events.findMomentumCandidates(NOW)).thenReturn(List.of(e));
        when(plans.refresh(e.getId())).thenReturn(Refresh.UNCHANGED);
        when(pruner.prune()).thenReturn(7);

        PlanRefreshJob.Result r = new PlanRefreshJob(plans, events, clock, null, invites, pruner).run();

        assertThat(r.pruned()).isEqualTo(7);
        InOrder order = inOrder(plans, pruner);
        order.verify(plans).refresh(e.getId());
        order.verify(pruner).prune();
    }

    @Test
    void run_aFailingPrune_isLogged_andKeepsTheRefreshCounts() {
        Event e = event();
        when(events.findMomentumCandidates(NOW)).thenReturn(List.of(e));
        when(plans.refresh(e.getId())).thenReturn(Refresh.CREATED);
        when(pruner.prune()).thenThrow(new IllegalStateException("fk"));

        PlanRefreshJob.Result r = new PlanRefreshJob(plans, events, clock, null, invites, pruner).run();

        assertThat(r).isEqualTo(new PlanRefreshJob.Result(1, 1, 0, 0, 0, -1));
    }

    @Test
    void run_withNoOnSaleEvents_writesNothing() {
        when(events.findMomentumCandidates(NOW)).thenReturn(List.of());
        assertThat(new PlanRefreshJob(plans, events, clock, null, invites, pruner).run())
                .isEqualTo(new PlanRefreshJob.Result(0, 0, 0, 0, 0, 0));
    }

    @Test
    void onEventPublished_refreshesThatEvent_andSwallowsAFailure() {
        UUID ok = UUID.randomUUID();
        UUID bad = UUID.randomUUID();
        when(plans.refresh(ok)).thenReturn(Refresh.CREATED);
        when(plans.refresh(bad)).thenThrow(new IllegalStateException("boom"));
        PlanRefreshJob job = new PlanRefreshJob(plans, events, clock, null, invites, pruner);

        job.onEventPublished(new PredictorReactivityEvents.EventPublished(ok));
        verify(plans).refresh(ok);
        assertThatCode(() -> job.onEventPublished(new PredictorReactivityEvents.EventPublished(bad)))
                .doesNotThrowAnyException();
    }

    @Test
    void onEventPublished_refreshesThePlan_thenRunsTheStoredInvitations() {
        UUID id = UUID.randomUUID();
        when(plans.refresh(id)).thenReturn(Refresh.CREATED);

        new PlanRefreshJob(plans, events, clock, null, invites, pruner)
                .onEventPublished(new PredictorReactivityEvents.EventPublished(id));

        InOrder order = inOrder(plans, invites, pruner);
        order.verify(plans).refresh(id);
        order.verify(invites).runOnPublish(id);
    }

    @Test
    void onEventPublished_stillRunsTheInvitations_whenTheRefreshFails() {
        UUID id = UUID.randomUUID();
        when(plans.refresh(id)).thenThrow(new IllegalStateException("boom"));

        new PlanRefreshJob(plans, events, clock, null, invites, pruner)
                .onEventPublished(new PredictorReactivityEvents.EventPublished(id));

        verify(invites).runOnPublish(id);
    }

    @Test
    void onEventPublished_swallowsAFailingInvitationRun() {
        UUID id = UUID.randomUUID();
        when(plans.refresh(id)).thenReturn(Refresh.UNCHANGED);
        doThrow(new IllegalStateException("db down")).when(invites).runOnPublish(id);
        PlanRefreshJob job = new PlanRefreshJob(plans, events, clock, null, invites, pruner);

        assertThatCode(() -> job.onEventPublished(new PredictorReactivityEvents.EventPublished(id)))
                .doesNotThrowAnyException();
        verify(invites).runOnPublish(id);
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduled_runsThroughTheProxy_andSwallowsAFailingRun() {
        PlanRefreshJob proxied = mock(PlanRefreshJob.class);
        when(proxied.run()).thenThrow(new IllegalStateException("db down"));
        ObjectProvider<PlanRefreshJob> self = mock(ObjectProvider.class);
        when(self.getObject()).thenReturn(proxied);

        assertThatCode(() -> new PlanRefreshJob(plans, events, clock, self, invites, pruner).scheduled()).doesNotThrowAnyException();
        verify(proxied).run();
    }

    private static Event event() {
        Event e = new Event();
        e.setId(UUID.randomUUID());
        return e;
    }
}

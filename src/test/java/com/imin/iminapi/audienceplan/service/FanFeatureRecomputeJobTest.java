package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.service.AudienceBackfillCompleted;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.audienceplan.repository.FanFeatureTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FanFeatureRecomputeJobTest {

    private static final Instant NOW = Instant.parse("2026-09-27T03:30:00Z");

    private FanFeatureRepository features;
    private FanFeatureProjector projector;
    private FanFeatureRecomputeJob proxied;
    private FanFeatureRecomputeJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        features = mock(FanFeatureRepository.class);
        projector = mock(FanFeatureProjector.class);
        proxied = mock(FanFeatureRecomputeJob.class);
        ObjectProvider<FanFeatureRecomputeJob> self = mock(ObjectProvider.class);
        when(self.getObject()).thenReturn(proxied);
        job = new FanFeatureRecomputeJob(features, projector, Clock.fixed(NOW, ZoneOffset.UTC), self);
    }

    // ---- triggers ----

    @Test
    void backfillCompleted_runsOneLockedRecompute() {
        job.onBackfillCompleted(new AudienceBackfillCompleted(3, 0));
        verify(proxied).recomputeAll();
    }

    @Test
    void backfillCompleted_recomputeFailure_isSwallowed() {
        doThrow(new IllegalStateException("boom")).when(proxied).recomputeAll();
        assertThatCode(() -> job.onBackfillCompleted(new AudienceBackfillCompleted(0, 0))).doesNotThrowAnyException();
    }

    @Test
    void fallback_everyMembershipFresh_skips() {
        when(features.countStale(NOW.minus(FanFeatureRecomputeJob.FRESHNESS))).thenReturn(0L);
        job.fallback();
        verify(proxied, never()).recomputeAll();
    }

    @Test
    void fallback_staleMembership_runsLockedRecompute() {
        when(features.countStale(NOW.minus(FanFeatureRecomputeJob.FRESHNESS))).thenReturn(4L);
        job.fallback();
        verify(proxied).recomputeAll();
    }

    // ---- paging ----

    @Test
    void recomputeAll_emptyRegistry_writesNothing() {
        when(features.findTargetsFirstPage(any())).thenReturn(List.of());
        job.recomputeAll();
        verify(projector, never()).recomputeBatch(any());
    }

    @Test
    void recomputeAll_fullPage_continuesAfterItsLastId_untilAShortPage() {
        List<FanFeatureTarget> first = targets(FanFeatureRecomputeJob.PAGE_SIZE);
        List<FanFeatureTarget> second = targets(2);
        when(features.findTargetsFirstPage(any())).thenReturn(first);
        UUID lastOfFirst = first.get(first.size() - 1).membershipId();
        when(features.findTargetsAfter(eq(lastOfFirst), any())).thenReturn(second);

        job.recomputeAll();

        verify(projector).recomputeBatch(first);
        verify(projector).recomputeBatch(second);
        verify(features, never()).findTargetsAfter(eq(second.get(1).membershipId()), any());
    }

    @Test
    void recomputeAll_exactMultipleOfPageSize_stopsOnTheEmptyPage() {
        List<FanFeatureTarget> first = targets(FanFeatureRecomputeJob.PAGE_SIZE);
        when(features.findTargetsFirstPage(any())).thenReturn(first);
        when(features.findTargetsAfter(any(), any())).thenReturn(List.of());

        job.recomputeAll();

        verify(projector).recomputeBatch(first);
        verify(features).findTargetsAfter(eq(first.get(first.size() - 1).membershipId()), any(Pageable.class));
    }

    @Test
    void recomputeAll_asksForPagesOfThePageSize() {
        when(features.findTargetsFirstPage(any())).thenReturn(List.of());
        job.recomputeAll();
        verify(features).findTargetsFirstPage(org.mockito.ArgumentMatchers.argThat(
                (Pageable p) -> p.getPageNumber() == 0 && p.getPageSize() == 500));
    }

    private static List<FanFeatureTarget> targets(int n) {
        List<FanFeatureTarget> out = new ArrayList<>();
        IntStream.range(0, n).forEach(i ->
                out.add(new FanFeatureTarget(UUID.randomUUID(), UUID.randomUUID(), "t" + i + "@example.com", false)));
        return out;
    }
}

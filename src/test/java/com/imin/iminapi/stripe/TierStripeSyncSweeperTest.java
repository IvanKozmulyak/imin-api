package com.imin.iminapi.stripe;

import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.repository.TicketTierRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TierStripeSyncSweeperTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    TicketTierRepository tiers = mock(TicketTierRepository.class);
    TierStripeSyncQueue queue = mock(TierStripeSyncQueue.class);
    TierStripeSyncSweeper sweeper = new TierStripeSyncSweeper(tiers, queue, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void nothingDue_noClaimNoRequest() {
        when(tiers.findStripeSyncSweepCandidates(any(), any(), any(), any())).thenReturn(List.of());

        sweeper.sweep();

        verify(tiers, never()).claimStripeSyncSweep(any(), anyInt(), any(), any());
        verify(queue, never()).request(any(), anyInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dueTier_claimedWithBackoffThenRequested() {
        UUID id = UUID.randomUUID();
        when(tiers.findStripeSyncSweepCandidates(any(), any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{id, 3}));
        when(tiers.claimStripeSyncSweep(id, 3, NOW.plus(Duration.ofMinutes(40)), NOW)).thenReturn(1);

        sweeper.sweep();

        ArgumentCaptor<Collection<EventStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(tiers).findStripeSyncSweepCandidates(statuses.capture(),
                eq(NOW.minus(Duration.ofMinutes(2))), eq(NOW), page.capture());
        assertThat(statuses.getValue()).containsExactly(EventStatus.DRAFT, EventStatus.LIVE);
        assertThat(page.getValue().getPageNumber()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(25);
        InOrder order = inOrder(tiers, queue);
        order.verify(tiers).claimStripeSyncSweep(id, 3, NOW.plus(Duration.ofMinutes(40)), NOW);
        order.verify(queue).request(id, 0);
    }

    @Test
    void lostClaim_notRequested() {
        UUID id = UUID.randomUUID();
        when(tiers.findStripeSyncSweepCandidates(any(), any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{id, 0}));
        when(tiers.claimStripeSyncSweep(any(), anyInt(), any(), any())).thenReturn(0);

        sweeper.sweep();

        verify(tiers).claimStripeSyncSweep(id, 0, NOW.plus(Duration.ofMinutes(5)), NOW);
        verify(queue, never()).request(any(), anyInt());
    }

    @Test
    void oneTierThrows_othersStillProcessed() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        when(tiers.findStripeSyncSweepCandidates(any(), any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{bad, 0}, new Object[]{good, 0}));
        when(tiers.claimStripeSyncSweep(eq(bad), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));
        when(tiers.claimStripeSyncSweep(eq(good), anyInt(), any(), any())).thenReturn(1);

        sweeper.sweep();

        verify(queue, never()).request(eq(bad), anyInt());
        verify(queue).request(good, 0);
    }

    @Test
    void nextAttemptAt_doublesFromFiveMinutes_cappedAtOneDay() {
        Duration base = TierStripeSyncSweeper.BASE;
        Duration ceiling = TierStripeSyncSweeper.CEILING;

        assertThat(TierStripeSyncSweeper.nextAttemptAt(NOW, 1)).isEqualTo(NOW.plus(base));
        assertThat(TierStripeSyncSweeper.nextAttemptAt(NOW, 2)).isEqualTo(NOW.plus(base.multipliedBy(2)));
        assertThat(TierStripeSyncSweeper.nextAttemptAt(NOW, 9)).isEqualTo(NOW.plus(base.multipliedBy(256)));
        assertThat(TierStripeSyncSweeper.nextAttemptAt(NOW, 9)).isEqualTo(NOW.plus(Duration.ofMinutes(1280)));
        assertThat(TierStripeSyncSweeper.nextAttemptAt(NOW, 10)).isEqualTo(NOW.plus(ceiling));
        assertThat(TierStripeSyncSweeper.nextAttemptAt(NOW, 40)).isEqualTo(NOW.plus(ceiling));
        assertThat(ceiling).isEqualTo(Duration.ofHours(24));
    }
}

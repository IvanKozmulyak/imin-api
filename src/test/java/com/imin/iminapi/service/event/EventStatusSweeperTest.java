package com.imin.iminapi.service.event;

import com.imin.iminapi.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link EventStatusSweeper}.
 *
 * <p>Uses pure Mockito — no Spring context needed. Verifies that the tick
 * delegates to the repository with the clock's current instant and handles
 * the zero-count case silently.
 */
class EventStatusSweeperTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-12T12:00:00Z"), ZoneOffset.UTC);
    private final Instant fixedNow = clock.instant();

    @Test
    void sweep_callsRepositoryWithClockInstant() {
        EventRepository repo = mock(EventRepository.class);
        when(repo.markLivePast(any(), any())).thenReturn(3);

        new EventStatusSweeper(repo, clock).sweep();

        ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
        // markLivePast takes (now, updatedAt) — both should be the clock instant
        verify(repo).markLivePast(nowCaptor.capture(), nowCaptor.capture());
        assertThat(nowCaptor.getAllValues()).allMatch(fixedNow::equals);
    }

    @Test
    void sweep_isNoOp_whenRepositoryReturnsZero() {
        EventRepository repo = mock(EventRepository.class);
        when(repo.markLivePast(any(), any())).thenReturn(0);

        // Must not throw; just silently returns
        new EventStatusSweeper(repo, clock).sweep();

        verify(repo).markLivePast(any(), any());
        verifyNoMoreInteractions(repo);
    }
}

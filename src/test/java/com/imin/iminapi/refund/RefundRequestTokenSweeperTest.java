package com.imin.iminapi.refund;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundRequestTokenSweeperTest {

    @Test
    void sweep_calls_repository_with_7_day_cutoff() {
        RefundRequestTokenRepository repo = mock(RefundRequestTokenRepository.class);
        when(repo.deleteExpiredBefore(any())).thenReturn(0);

        Instant before = Instant.now();
        new RefundRequestTokenSweeper(repo).sweep();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repo).deleteExpiredBefore(cutoff.capture());

        // Cutoff should be ~7 days before "now"; allow a small wall-clock window
        // to account for the test invocation taking non-zero time.
        Instant lowerBound = before.minus(Duration.ofDays(7)).minusSeconds(1);
        Instant upperBound = after.minus(Duration.ofDays(7)).plusSeconds(1);
        assertThat(cutoff.getValue()).isBetween(lowerBound, upperBound);
    }

    @Test
    void sweep_swallows_zero_deletions_silently() {
        RefundRequestTokenRepository repo = mock(RefundRequestTokenRepository.class);
        when(repo.deleteExpiredBefore(any())).thenReturn(0);

        // Should not throw when the repo reports nothing to delete.
        new RefundRequestTokenSweeper(repo).sweep();

        verify(repo).deleteExpiredBefore(any());
    }
}

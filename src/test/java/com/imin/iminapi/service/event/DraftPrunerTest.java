package com.imin.iminapi.service.event;

import com.imin.iminapi.repository.EventRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DraftPrunerTest {

    EventRepository repo = mock(EventRepository.class);

    @Test
    void purge_deletes_drafts_older_than_24h() {
        when(repo.deleteEmptyDraftsOlderThan(any())).thenReturn(7);
        DraftPruner sut = new DraftPruner(repo);

        sut.purge();

        verify(repo).deleteEmptyDraftsOlderThan(argThat(cutoff -> {
            Instant now = Instant.now();
            Duration delta = Duration.between(cutoff, now);
            return delta.toHours() >= 23 && delta.toHours() <= 25;
        }));
    }
}

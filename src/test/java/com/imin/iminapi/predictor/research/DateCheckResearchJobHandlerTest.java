package com.imin.iminapi.predictor.research;

import com.imin.iminapi.predictor.config.DateCheckAccess;
import com.imin.iminapi.predictor.jobs.PredictorJobService;
import com.imin.iminapi.predictor.model.PredictorJob;
import com.imin.iminapi.predictor.service.DateCheckService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A research job whose result cannot be stored: the last attempt marks the research failed, never hiding the error. */
class DateCheckResearchJobHandlerTest {

    private final DateCheckService checks = mock(DateCheckService.class);
    private final WebResearchService research = mock(WebResearchService.class);
    private final DateCheckAccess access = mock(DateCheckAccess.class);
    private final DateCheckResearchJobHandler handler = new DateCheckResearchJobHandler(checks, research, access);

    private final UUID checkId = UUID.randomUUID();
    private final RuntimeException boom = new IllegalStateException("db down");
    private final PredictorJob job = new PredictorJob();

    @BeforeEach
    void storingTheResultFails() {
        UUID org = UUID.randomUUID();
        WebResearchService.Request req = new WebResearchService.Request(org, "Paris", "FR", "house & techno", null,
                List.of(LocalDate.of(2026, 10, 17)));
        WebResearchService.Outcome outcome = new WebResearchService.Outcome(true, Map.of(), null, "m", null);
        when(checks.researchSnapshot(checkId)).thenReturn(Optional.of(req));
        when(access.isResearchAvailable(org)).thenReturn(true);
        when(research.research(req)).thenReturn(outcome);
        doThrow(boom).when(checks).completeResearch(checkId, outcome);
        job.setPayloadJson("{\"dateCheckId\":\"" + checkId + "\"}");
    }

    @Test
    void earlierAttemptRethrowsAndLeavesResearchRunning() {
        job.setAttempts(PredictorJobService.MAX_ATTEMPTS - 1);

        assertThatThrownBy(() -> handler.run(job)).isSameAs(boom);

        verify(checks, never()).failResearch(any());
    }

    @Test
    void lastAttemptMarksResearchFailedAndRethrows() {
        job.setAttempts(PredictorJobService.MAX_ATTEMPTS);

        assertThatThrownBy(() -> handler.run(job)).isSameAs(boom);

        verify(checks).failResearch(checkId);
        assertThat(boom.getSuppressed()).isEmpty();
    }

    @Test
    void failingCleanupIsSuppressedUnderTheOriginalError() {
        job.setAttempts(PredictorJobService.MAX_ATTEMPTS);
        RuntimeException cleanup = new IllegalStateException("still down");
        doThrow(cleanup).when(checks).failResearch(checkId);

        assertThatThrownBy(() -> handler.run(job)).isSameAs(boom)
                .satisfies(t -> assertThat(t.getSuppressed()).containsExactly(cleanup));
    }
}

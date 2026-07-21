package com.imin.iminapi.predictor;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.dto.PredictionResult;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.service.PredictionInputSnapshot;
import com.imin.iminapi.predictor.service.PredictionLedgerService;
import com.imin.iminapi.predictor.service.PredictionRequestService;
import com.imin.iminapi.predictor.service.PredictionScoringPipeline;
import com.imin.iminapi.predictor.service.PredictorJson;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.ai.AiQuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Request-surface ordering (task 86cav4766): the cache short-circuit consumes neither
 * throttle nor quota; the predictor-rescore bucket 429 fires BEFORE quota; quota is only
 * consumed for real LLM runs.
 */
class PredictionRequestServiceTest {

    private final EventRepository events = mock(EventRepository.class);
    private final PredictionLedgerRepository ledgerRepo = mock(PredictionLedgerRepository.class);
    private final PredictionLedgerService ledgerService = mock(PredictionLedgerService.class);
    private final PredictionScoringPipeline pipeline = mock(PredictionScoringPipeline.class);
    private final AiQuotaService quota = mock(AiQuotaService.class);
    private final RateLimiter limiter = mock(RateLimiter.class);

    private final PredictionRequestService sut = new PredictionRequestService(
            events, ledgerRepo, ledgerService, pipeline, quota, limiter, Runnable::run);

    private final UUID eventId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final AuthPrincipal principal =
            new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());

    private Event event;
    private PredictionInputSnapshot snap;

    @BeforeEach
    void setUp() {
        event = new Event();
        event.setId(eventId);
        event.setOrgId(orgId);
        when(events.findActive(eventId)).thenReturn(Optional.of(event));
        snap = new PredictionInputSnapshot(
                PredictionInputSnapshot.SNAPSHOT_VERSION, eventId,
                "Amsterdam", "NL", "techno", 250, "B101_300",
                null, null, "SUMMER", 30, "EUR", List.of(), List.of(), 10, 2, true, List.of(),
                new PredictionInputSnapshot.CorpusLine("NONE", 0, 0, 0, List.of(), null));
        when(pipeline.snapshot(event)).thenReturn(snap);
        when(pipeline.score(any(), any())).thenReturn(new PredictionScoringPipeline.Scored(
                UUID.randomUUID(), benchmark(), snap.sha256()));
    }

    private PredictionResult benchmark() {
        return new PredictionResult("pre_publish", 0, "C", null, null, null,
                List.of(), List.of(), null, true, "m", "1.0.0", Instant.now());
    }

    @Test
    void cacheHitConsumesNeitherThrottleNorQuota() throws Exception {
        PredictionLedger row = new PredictionLedger();
        row.setId(UUID.randomUUID());
        row.setEventId(eventId);
        row.setSurface(PredictionSurface.PRE_PUBLISH);
        row.setInputSnapshotHash(snap.sha256());
        row.setOutputJson(PredictorJson.MAPPER.writeValueAsString(benchmark()));
        when(ledgerRepo.findByEventIdOrderByCreatedAtDesc(eventId)).thenReturn(List.of(row));

        PredictionRequestService.Trigger t = sut.trigger(principal, eventId);

        assertThat(t.httpStatus()).isEqualTo(200);
        assertThat(t.body().cached()).isTrue();
        verify(limiter, never()).consume(anyString(), anyString());
        verify(quota, never()).checkAndRecordScore(any());
        verify(pipeline, never()).score(any(), any());
    }

    @Test
    void throttleFiresBeforeQuota() {
        doThrow(ApiException.rateLimited()).when(limiter).consume(eq("predictor-rescore"), anyString());

        assertThatThrownBy(() -> sut.trigger(principal, eventId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(ErrorCode.RATE_LIMITED));
        verify(quota, never()).checkAndRecordScore(any());
    }

    @Test
    void realRunConsumesThrottleThenQuotaThenScores() {
        PredictionRequestService.Trigger t = sut.trigger(principal, eventId);

        assertThat(t.httpStatus()).isEqualTo(202);
        verify(limiter).consume(eq("predictor-rescore"), anyString());
        verify(quota).checkAndRecordScore(principal);
        verify(pipeline).score(eq(event), any());
    }

    @Test
    void killSwitchRunSkipsQuota() {
        when(pipeline.killSwitchActive()).thenReturn(true);

        PredictionRequestService.Trigger t = sut.trigger(principal, eventId);

        assertThat(t.httpStatus()).isEqualTo(202);
        verify(limiter).consume(eq("predictor-rescore"), anyString());
        verify(quota, never()).checkAndRecordScore(any()); // benchmark-only is free
        verify(pipeline).score(eq(event), any());
    }
}

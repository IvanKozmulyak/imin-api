package com.imin.iminapi.service;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.EventCreatorResponse;
import com.imin.iminapi.dto.GeneratedPoster;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PosterVariant;
import com.imin.iminapi.exception.EventCreationException;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.model.GeneratedEventStatus;
import com.imin.iminapi.repository.GeneratedEventRepository;
import com.imin.iminapi.service.poster.PosterOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventCreatorServiceTest {

    @Mock private AiEventDescriptionService aiEventDescriptionService;
    @Mock private PosterOrchestrator posterOrchestrator;
    @Mock private GeneratedEventRepository repository;

    private EventCreatorService service;

    @BeforeEach
    void setUp() {
        service = new EventCreatorService(aiEventDescriptionService, posterOrchestrator, repository);
    }

    private EventCreatorRequest request() {
        return new EventCreatorRequest(
                "underground techno night", "edgy", "techno", "Berlin",
                LocalDate.of(2026, 6, 14), List.of("INSTAGRAM"),
                null, null, "Void Sessions IV", null,
                "Kreuzberg 12, Berlin", "https://imin.wtf/e/abc", null);
    }

    private PosterConcept concept() {
        PosterVariant v = new PosterVariant("atmospheric", "p".repeat(200), "3:4", "Design");
        return new PosterConcept("neon_underground", "magenta + cyan", List.of(v, v, v));
    }

    private PosterOrchestrator.OrchestrationResult orchestrationResult() {
        GeneratedPoster poster = new GeneratedPoster(
                UUID.randomUUID(), "atmospheric",
                "/images/raw.png", "/images/final.png",
                42L, "prompt", List.of("ref1"), Map.of("qr_code", true), "COMPLETE", null);
        return new PosterOrchestrator.OrchestrationResult(UUID.randomUUID(), "neon_underground", List.of(poster));
    }

    @Test
    void create_successfulRun_persistsDraftThenComplete() {
        when(aiEventDescriptionService.generateConcept(any())).thenReturn(concept());
        when(posterOrchestrator.run(any(), any(), any())).thenReturn(orchestrationResult());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EventCreatorResponse response = service.create(request());

        assertThat(response.status()).isEqualTo("COMPLETE");
        assertThat(response.subStyleTag()).isEqualTo("neon_underground");
        assertThat(response.posters()).hasSize(1);
        assertThat(response.posters().get(0).finalUrl()).isEqualTo("/images/final.png");
    }

    @Test
    void create_orchestratorFailure_setsStatusFailedAndThrows() {
        when(aiEventDescriptionService.generateConcept(any())).thenReturn(concept());
        when(posterOrchestrator.run(any(), any(), any()))
                .thenThrow(new RuntimeException("Replicate error"));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.create(request()))
                .isInstanceOf(EventCreationException.class)
                .hasMessageContaining("Replicate error");
    }

    @Test
    void create_persistsDraftImmediately() {
        when(aiEventDescriptionService.generateConcept(any())).thenReturn(concept());
        when(posterOrchestrator.run(any(), any(), any())).thenReturn(orchestrationResult());

        List<GeneratedEventStatus> statuses = new java.util.ArrayList<>();
        when(repository.save(any())).thenAnswer(inv -> {
            statuses.add(((GeneratedEvent) inv.getArgument(0)).getStatus());
            return inv.getArgument(0);
        });

        service.create(request());

        assertThat(statuses).containsExactly(GeneratedEventStatus.DRAFT, GeneratedEventStatus.COMPLETE);
    }

    @Test
    void create_withSubStyleTagOverride_passesOverriddenTagToOrchestrator() {
        EventCreatorRequest req = new EventCreatorRequest(
                "underground techno night", "edgy", "techno", "Berlin",
                LocalDate.of(2026, 6, 14), List.of("INSTAGRAM"),
                null, null, "Void Sessions IV", null,
                "Kreuzberg 12, Berlin", "https://imin.wtf/e/abc",
                "chrome_tropical");

        // AI returns its own pick — the service must override it.
        when(aiEventDescriptionService.generateConcept(any())).thenReturn(concept());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        org.mockito.ArgumentCaptor<PosterConcept> conceptCaptor =
                org.mockito.ArgumentCaptor.forClass(PosterConcept.class);
        when(posterOrchestrator.run(any(), any(), conceptCaptor.capture()))
                .thenReturn(orchestrationResult());

        service.create(req);

        assertThat(conceptCaptor.getValue().subStyleTag()).isEqualTo("chrome_tropical");
    }
}

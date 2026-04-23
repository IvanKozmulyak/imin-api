package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.GeneratedPoster;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PosterVariant;
import com.imin.iminapi.dto.ReferenceImageSet;
import com.imin.iminapi.model.ImageProvider;
import com.imin.iminapi.model.PosterGeneration;
import com.imin.iminapi.model.PosterGenerationStatus;
import com.imin.iminapi.repository.PosterGenerationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PosterOrchestratorTest {

    @Mock IdeogramClient ideogramClient;
    @Mock OpenAiImageClient openAiImageClient;
    @Mock ReferenceImageLibrary referenceLibrary;
    @Mock OverlayCompositor overlayCompositor;
    @Mock PosterImageStorage storage;
    @Mock PosterGenerationRepository generationRepository;

    private PosterOrchestrator orchestrator() {
        return new PosterOrchestrator(
                ideogramClient, openAiImageClient, referenceLibrary,
                overlayCompositor, storage, generationRepository, 6);
    }

    private EventCreatorRequest req() {
        return req(null);
    }

    private EventCreatorRequest req(ImageProvider provider) {
        return new EventCreatorRequest(
                "vibe", "tone", "techno", "Berlin",
                LocalDate.of(2026, 6, 14), List.of("INSTAGRAM"),
                null, null, "Void", null,
                "Kreuzberg", "https://imin.wtf/e/1", null, provider);
    }

    private PosterConcept concept() {
        PosterVariant v1 = new PosterVariant("atmospheric", "p".repeat(200), "3:4", "Design");
        PosterVariant v2 = new PosterVariant("graphic", "p".repeat(200), "1:1", "Design");
        PosterVariant v3 = new PosterVariant("minimal", "p".repeat(200), "4:5", "Design");
        return new PosterConcept("neon_underground", "magenta + cyan", List.of(v1, v2, v3));
    }

    private void stubRepoAssignsId() {
        when(generationRepository.save(any())).thenAnswer(inv -> {
            PosterGeneration g = inv.getArgument(0);
            if (g.getId() == null) g.setId(UUID.randomUUID());
            return g;
        });
    }

    @Test
    void run_allThreeSucceed_statusComplete() {
        stubRepoAssignsId();
        when(referenceLibrary.forTag("neon_underground"))
                .thenReturn(new ReferenceImageSet("neon_underground", List.of("https://r/1.jpg"), List.of("1.jpg")));
        when(ideogramClient.generate(any(), any(), any(), anyLong(), any()))
                .thenReturn(new IdeogramClient.IdeogramResult(
                        "https://replicate.delivery/x.png", 1L, Duration.ofMillis(50), "turbo"));
        when(storage.download(any())).thenReturn(new byte[]{1, 2, 3});
        when(storage.writePng(any())).thenReturn("/images/out.png");
        when(overlayCompositor.applyOverlays(any())).thenReturn(new byte[]{4, 5, 6});

        PosterOrchestrator.OrchestrationResult result =
                orchestrator().run(UUID.randomUUID(), req(), concept());

        assertThat(result.posters()).hasSize(3);
        assertThat(result.posters()).allMatch(p -> "COMPLETE".equals(p.status()));

        ArgumentCaptor<PosterGeneration> saves = ArgumentCaptor.forClass(PosterGeneration.class);
        verify(generationRepository, atLeastOnce()).save(saves.capture());
        PosterGeneration last = saves.getAllValues().get(saves.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(PosterGenerationStatus.COMPLETE);
    }

    @Test
    void run_oneFails_stillReturnsCompletePartial() {
        stubRepoAssignsId();
        when(referenceLibrary.forTag(any())).thenReturn(new ReferenceImageSet("neon_underground", List.of(), List.of()));

        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(ideogramClient.generate(any(), any(), any(), anyLong(), any())).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            if (n == 2) throw new RuntimeException("replicate 500");
            return new IdeogramClient.IdeogramResult(
                    "https://replicate.delivery/ok.png", 1L, Duration.ofMillis(50), "turbo");
        });
        when(storage.download(any())).thenReturn(new byte[]{1});
        when(storage.writePng(any())).thenReturn("/images/ok.png");
        when(overlayCompositor.applyOverlays(any())).thenReturn(new byte[]{2});

        PosterOrchestrator.OrchestrationResult result =
                orchestrator().run(UUID.randomUUID(), req(), concept());

        assertThat(result.posters()).hasSize(3);
        long failed = result.posters().stream().filter(p -> "FAILED".equals(p.status())).count();
        long complete = result.posters().stream().filter(p -> "COMPLETE".equals(p.status())).count();
        assertThat(failed).isEqualTo(1);
        assertThat(complete).isEqualTo(2);
    }

    @Test
    void run_withOpenAiProvider_routesToOpenAiClient() {
        stubRepoAssignsId();
        when(referenceLibrary.forTag("neon_underground"))
                .thenReturn(new ReferenceImageSet("neon_underground", List.of("data:..."), List.of("1.png")));
        when(referenceLibrary.loadAllBytes("neon_underground"))
                .thenReturn(List.of(new byte[]{9, 9, 9}));
        when(openAiImageClient.generate(any(), any(), any(), anyLong()))
                .thenReturn(new OpenAiImageClient.OpenAiImageResult(
                        new byte[]{7, 7, 7}, Duration.ofMillis(50), "gpt-image-1"));
        when(storage.writePng(any())).thenReturn("/images/oai.png");
        when(overlayCompositor.applyOverlays(any())).thenReturn(new byte[]{8, 8, 8});

        PosterOrchestrator.OrchestrationResult result =
                orchestrator().run(UUID.randomUUID(), req(ImageProvider.OPENAI), concept());

        assertThat(result.posters()).hasSize(3);
        assertThat(result.posters()).allMatch(p -> "COMPLETE".equals(p.status()));
        verify(openAiImageClient, atLeastOnce()).generate(any(), any(), any(), anyLong());
        verify(ideogramClient, org.mockito.Mockito.never()).generate(any(), any(), any(), anyLong(), any());
    }

    @Test
    void run_allFail_throws() {
        stubRepoAssignsId();
        when(referenceLibrary.forTag(any())).thenReturn(new ReferenceImageSet("neon_underground", List.of(), List.of()));
        when(ideogramClient.generate(any(), any(), any(), anyLong(), any()))
                .thenThrow(new RuntimeException("replicate down"));

        assertThatThrownBy(() -> orchestrator().run(UUID.randomUUID(), req(), concept()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("All 3 poster variants failed");
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}

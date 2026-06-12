package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PosterTextSpec;
import com.imin.iminapi.dto.PosterVariant;
import com.imin.iminapi.dto.StyleCard;
import com.imin.iminapi.dto.StyleReferencePart;
import com.imin.iminapi.dto.Vibe;
import com.imin.iminapi.model.PosterGeneration;
import com.imin.iminapi.model.PosterVariantEntity;
import com.imin.iminapi.repository.PosterGenerationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PosterOrchestratorTest {

    private IdeogramV3Client ideogram;
    private VibeLibrary vibeLibrary;
    private StyleCardLibrary styleCardLibrary;
    private ReferenceImageLibrary referenceLibrary;
    private PosterTextSpecFactory textSpecFactory;
    private PosterTextValidationService textValidation;
    private PosterStyleValidationService styleValidation;
    private PosterImageStorage storage;
    private PosterGenerationRepository repo;
    private BrandLogoCompositor logoCompositor;

    @BeforeEach
    void setUp() {
        ideogram = mock(IdeogramV3Client.class);
        vibeLibrary = mock(VibeLibrary.class);
        styleCardLibrary = mock(StyleCardLibrary.class);
        referenceLibrary = mock(ReferenceImageLibrary.class);
        textSpecFactory = mock(PosterTextSpecFactory.class);
        textValidation = mock(PosterTextValidationService.class);
        styleValidation = mock(PosterStyleValidationService.class);
        storage = mock(PosterImageStorage.class);
        repo = mock(PosterGenerationRepository.class);
        logoCompositor = mock(BrandLogoCompositor.class);

        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.writePng(any())).thenReturn("https://img/x.png");
        when(textSpecFactory.from(any()))
                .thenReturn(new PosterTextSpec(List.of("TITLE"), List.of("TITLE"), "block"));
        when(referenceLibrary.topReferenceParts(any(), anyInt(), anyLong()))
                .thenReturn(List.of(new StyleReferencePart(new byte[]{1}, "r0.png", "image/png")));
        when(vibeLibrary.byId(any())).thenReturn(Optional.of(brutalist()));
        when(vibeLibrary.ideogramStylePreset(any())).thenReturn("HIGH_CONTRAST");
        when(styleCardLibrary.get(any())).thenReturn(Optional.of(mock(StyleCard.class)));
    }

    private PosterOrchestrator orchestrator() {
        return new PosterOrchestrator(ideogram, vibeLibrary, styleCardLibrary, referenceLibrary,
                textSpecFactory, textValidation, styleValidation, storage, logoCompositor, repo,
                /*maxRegenerations*/ 2, /*remixImageWeight*/ 70, /*maxReferences*/ 3, /*maxConcurrent*/ 6);
    }

    private static Vibe brutalist() {
        return new Vibe("brutalist_techno", "Brutalist Techno", List.of("techno"), "vs",
                List.of("#000"), "typo", "comp", List.of(), List.of(), null, List.of(), null,
                null, false, "subj", null, null);
    }

    private static EventCreatorRequest req() {
        return new EventCreatorRequest("v", "energetic", "techno", "Berlin", LocalDate.now(),
                List.of("instagram"), null, null, "Big Night", null, null, null, "brutalist_techno", null);
    }

    private static PosterConcept concept() {
        List<PosterVariant> vs = List.of(
                new PosterVariant("people", "people prompt with TITLE", "4:5", "Design"),
                new PosterVariant("object", "object prompt with TITLE", "4:5", "Design"),
                new PosterVariant("typographic", "typographic prompt with TITLE", "4:5", "Design"));
        return new PosterConcept("brutalist_techno", "colors", vs);
    }

    private static PosterTextValidationService.ValidationDecision textOk() {
        return PosterTextValidationService.ValidationDecision.pass();
    }

    private static PosterTextValidationService.ValidationDecision textFail() {
        return new PosterTextValidationService.ValidationDecision(false, "missing", List.of("TITLE"), List.of("BLRG"));
    }

    private static PosterStyleValidationService.ValidationDecision styleOk() {
        return new PosterStyleValidationService.ValidationDecision(true, null);
    }

    private static PosterStyleValidationService.ValidationDecision styleFail() {
        return new PosterStyleValidationService.ValidationDecision(false, "wrong hero");
    }

    @Test
    void happyPath_generateOnce_textAndStylePass_acceptedVerdict() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

        PosterOrchestrator.OrchestrationResult r =
                orchestrator().run(UUID.randomUUID(), req(), concept());

        assertThat(r.posters()).hasSize(3);
        assertThat(r.posters()).allSatisfy(p -> assertThat(p.status()).isEqualTo("COMPLETE"));
        verify(ideogram, never()).remix(any(), any(), anyInt(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void textFails_thenRemixCorrects_remixPromptCarriesMissingText() {
        // generate yields {1} (text-fail), remix yields {2} (text-pass) — keyed on the image bytes so the
        // shared mock stays deterministic across the 3 parallel variant threads.
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{1}, 1L));
        when(ideogram.remix(any(), any(), anyInt(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 2L));
        when(textValidation.validateOrExplain(any(), any())).thenAnswer(inv -> {
            byte[] img = inv.getArgument(0);
            return (img.length > 0 && img[0] == 1) ? textFail() : textOk();
        });
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

        PosterOrchestrator.OrchestrationResult r =
                orchestrator().run(UUID.randomUUID(), req(), concept());

        assertThat(r.posters()).allSatisfy(p -> assertThat(p.status()).isEqualTo("COMPLETE"));
        verify(ideogram, times(3)).remix(any(), any(), eq(70), anyLong(), any(), any(), any(), any());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ideogram, times(3)).remix(any(), prompt.capture(), anyInt(), anyLong(), any(), any(), any(), any());
        assertThat(prompt.getValue()).contains("CORRECTION").contains("TITLE");
    }

    @Test
    void textNeverPasses_acceptsBestEffortWithJournal() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{1}, 1L));
        when(ideogram.remix(any(), any(), anyInt(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{1}, 2L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textFail());

        PosterOrchestrator.OrchestrationResult r =
                orchestrator().run(UUID.randomUUID(), req(), concept());

        // 2 remixes (maxRegenerations=2) per variant, then best-effort COMPLETE
        verify(ideogram, times(6)).remix(any(), any(), anyInt(), anyLong(), any(), any(), any(), any());
        assertThat(r.posters()).allSatisfy(p -> assertThat(p.status()).isEqualTo("COMPLETE"));
        ArgumentCaptor<PosterGeneration> saved = ArgumentCaptor.forClass(PosterGeneration.class);
        verify(repo, atLeastOnce()).save(saved.capture());
        PosterVariantEntity v = saved.getValue().getVariants().get(0);
        assertThat(v.getValidationVerdict()).isEqualTo("BEST_EFFORT");
        assertThat(v.getValidationAttemptsJson()).contains("remix");
    }

    @Test
    void textPasses_styleSoftFails_acceptsBestEffort_noRemix() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleFail());

        PosterOrchestrator.OrchestrationResult r =
                orchestrator().run(UUID.randomUUID(), req(), concept());

        assertThat(r.posters()).allSatisfy(p -> assertThat(p.status()).isEqualTo("COMPLETE"));
        verify(ideogram, never()).remix(any(), any(), anyInt(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void brandColors_forwardedToIdeogramGenerateAndRemix() {
        // generate yields {1} (text-fail) so each variant also exercises the remix path.
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{1}, 1L));
        when(ideogram.remix(any(), any(), anyInt(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 2L));
        when(textValidation.validateOrExplain(any(), any())).thenAnswer(inv -> {
            byte[] img = inv.getArgument(0);
            return (img.length > 0 && img[0] == 1) ? textFail() : textOk();
        });
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

        BrandSnapshot brand = new BrandSnapshot(List.of("#ec4899", "#f6c04a"), null, false);
        orchestrator().run(UUID.randomUUID(), req(), concept(), 123L, List.of(), brand);

        verify(ideogram, times(3)).generate(any(), anyLong(), any(), any(),
                eq(List.of("#ec4899", "#f6c04a")), any());
        verify(ideogram, times(3)).remix(any(), any(), anyInt(), anyLong(), any(), any(),
                eq(List.of("#ec4899", "#f6c04a")), any());
    }

    @Test
    void noBrand_sendsEmptyPaletteToIdeogram() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

        orchestrator().run(UUID.randomUUID(), req(), concept());

        verify(ideogram, times(3)).generate(any(), anyLong(), any(), any(), eq(List.of()), any());
    }

    @Test
    void noBrand_skipsComposite_finalEqualsRaw() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

        PosterOrchestrator.OrchestrationResult r =
                orchestrator().run(UUID.randomUUID(), req(), concept());

        // No second writePng beyond the one raw write per variant; compositor never called.
        verify(logoCompositor, never()).composite(any(), any());
        assertThat(r.posters()).allSatisfy(p -> assertThat(p.finalUrl()).isEqualTo(p.rawUrl()));
    }

    @Test
    void brandWithLogoOn_appliesComposite_finalDiffersFromRaw_andStatusApplied() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());
        // CRITICAL: the 3 variants render in PARALLEL on variantPool, and the branded path calls
        // writePng TWICE per variant (raw render bytes {2}, then the composite bytes {7}) — 6 calls
        // across 3 threads. A sequential thenReturn(...) would draw non-deterministically, so key
        // writePng on its byte[] argument (exactly as the existing remix test keys validateOrExplain
        // on the image bytes for the same reason). Composite mock returns {7}; raw render is {2}.
        when(storage.writePng(any())).thenAnswer(inv -> {
            byte[] b = inv.getArgument(0);
            return (b.length > 0 && b[0] == 7) ? "https://img/composited.png" : "https://img/raw.png";
        });
        when(storage.download("https://img/raw.png")).thenReturn(new byte[]{9});
        when(logoCompositor.composite(any(), eq("https://cdn/logo.png")))
                .thenReturn(new byte[]{7});

        BrandSnapshot brand = new BrandSnapshot(java.util.List.of("#ec4899"), "https://cdn/logo.png", true);
        PosterOrchestrator.OrchestrationResult r = orchestrator().run(
                UUID.randomUUID(), req(), concept(), 123L, java.util.List.of(), brand);

        // Assert across ALL variants (not .get(0)) — placement among the 3 parallel results is not ordered.
        assertThat(r.posters()).allSatisfy(p -> {
            assertThat(p.finalUrl()).isEqualTo("https://img/composited.png");
            assertThat(p.rawUrl()).isEqualTo("https://img/raw.png");
        });

        ArgumentCaptor<PosterGeneration> saved = ArgumentCaptor.forClass(PosterGeneration.class);
        verify(repo, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getBrandSnapshot()).contains("#ec4899");
        assertThat(saved.getValue().getVariants())
                .allSatisfy(v -> assertThat(v.getLogoCompositeStatus()).isEqualTo("APPLIED"));
    }

    @Test
    void compositeThrows_isIsolated_finalFallsBackToRaw_statusFailed() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());
        when(storage.writePng(any())).thenReturn("https://img/raw.png");
        when(storage.download("https://img/raw.png")).thenReturn(new byte[]{9});
        when(logoCompositor.composite(any(), any()))
                .thenThrow(new RuntimeException("decode boom"));

        BrandSnapshot brand = new BrandSnapshot(java.util.List.of("#ec4899"), "https://cdn/logo.png", true);
        PosterOrchestrator.OrchestrationResult r = orchestrator().run(
                UUID.randomUUID(), req(), concept(), 123L, java.util.List.of(), brand);

        // Generation still succeeds; final_url falls back to raw_url; status FAILED for every variant.
        assertThat(r.posters()).allSatisfy(p -> {
            assertThat(p.status()).isEqualTo("COMPLETE");
            assertThat(p.finalUrl()).isEqualTo("https://img/raw.png");
            assertThat(p.rawUrl()).isEqualTo("https://img/raw.png");
        });
        ArgumentCaptor<PosterGeneration> saved = ArgumentCaptor.forClass(PosterGeneration.class);
        verify(repo, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getVariants())
                .allSatisfy(v -> assertThat(v.getLogoCompositeStatus()).isEqualTo("FAILED"));
    }
}

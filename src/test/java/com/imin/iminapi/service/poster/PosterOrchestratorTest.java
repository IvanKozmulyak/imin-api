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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
        when(storage.writePng(any(), any())).thenReturn("https://img/x.png");
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
                /*maxRegenerations*/ 2,
                /*paletteRegradeEnabled*/ true, /*paletteRegradeWeight*/ 85,
                /*maxReferences*/ 3, /*maxConcurrent*/ 6);
    }

    /** Same wiring as {@link #orchestrator()} but with a caller-supplied style gate. */
    private PosterOrchestrator orchestratorWithStyleGate(PosterStyleValidationService gate) {
        return new PosterOrchestrator(ideogram, vibeLibrary, styleCardLibrary, referenceLibrary,
                textSpecFactory, textValidation, gate, storage, logoCompositor, repo,
                /*maxRegenerations*/ 2,
                /*paletteRegradeEnabled*/ true, /*paletteRegradeWeight*/ 85,
                /*maxReferences*/ 3, /*maxConcurrent*/ 6);
    }

    private static Vibe brutalist() {
        return new Vibe("brutalist_techno", "Brutalist Techno", List.of("techno"), "vs",
                List.of("#000"), "typo", "comp", List.of(), List.of(), null, List.of(), null,
                null, false, "subj", null, null);
    }

    private static EventCreatorRequest req() {
        return new EventCreatorRequest("v", "energetic", "techno", "Berlin", LocalDate.now(),
                List.of("instagram"), null, null, "Big Night", null, null, null, "brutalist_techno");
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
                orchestrator().run(UUID.randomUUID(), req(), concept(), 1L, List.of(), null, null, null);

        assertThat(r.posters()).hasSize(3);
        assertThat(r.posters()).allSatisfy(p -> assertThat(p.status()).isEqualTo("COMPLETE"));
        verify(ideogram, never()).remix(any(), any(), anyInt(), anyLong(), any(), any(), any());
    }

    @Test
    void textNeverPasses_acceptsBestEffortWithJournal() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{1}, 1L));
        when(ideogram.remix(any(), any(), anyInt(), anyLong(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{1}, 2L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textFail());

        PosterOrchestrator.OrchestrationResult r =
                orchestrator().run(UUID.randomUUID(), req(), concept(), 1L, List.of(), null, null, null);

        // Missing text: 2 fresh regenerates (maxRegenerations=2) per variant, then best-effort COMPLETE
        verify(ideogram, times(9)).generate(any(), anyLong(), any(), any(), any(), any());
        verify(ideogram, never()).remix(any(), any(), anyInt(), anyLong(), any(), any(), any());
        assertThat(r.posters()).allSatisfy(p -> assertThat(p.status()).isEqualTo("COMPLETE"));
        ArgumentCaptor<PosterGeneration> saved = ArgumentCaptor.forClass(PosterGeneration.class);
        verify(repo, atLeastOnce()).save(saved.capture());
        PosterVariantEntity v = saved.getValue().getVariants().get(0);
        assertThat(v.getValidationVerdict()).isEqualTo("BEST_EFFORT");
        assertThat(v.getValidationAttemptsJson()).contains("regenerate");
    }

    /**
     * poster-18: every text-gate retry used to write a fresh immutable R2 object that nothing ever
     * referenced or reclaimed — up to 3 orphans per variant at maxRegenerations=2. Only the render
     * that actually ships gets written.
     */
    @Test
    void supersededRetryRenders_areNeverWritten_onlyTheAcceptedOne() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{1}, 1L));
        when(ideogram.remix(any(), any(), anyInt(), anyLong(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{1}, 2L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textFail());

        PosterOrchestrator.OrchestrationResult r =
                orchestrator().run(UUID.randomUUID(), req(), concept(), 1L, List.of(), null, null, null);

        // 3 attempts x 3 variants rendered, but exactly one stored object per variant.
        verify(ideogram, times(9)).generate(any(), anyLong(), any(), any(), any(), any());
        verify(storage, times(3)).writePng(any(), any());
        assertThat(r.posters()).allSatisfy(p -> assertThat(p.rawUrl()).isNotNull());
    }

    @Test
    void textPasses_styleSoftFails_acceptsBestEffort_noRemix() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleFail());

        PosterOrchestrator.OrchestrationResult r =
                orchestrator().run(UUID.randomUUID(), req(), concept(), 1L, List.of(), null, null, null);

        assertThat(r.posters()).allSatisfy(p -> assertThat(p.status()).isEqualTo("COMPLETE"));
        verify(ideogram, never()).remix(any(), any(), anyInt(), anyLong(), any(), any(), any());
    }

    /**
     * poster-3: the SOFT style gate throwing must not fail a text-accepted variant. With the real
     * service wrapping a throwing client, all three renders still ship (best-effort).
     */
    @Test
    void styleGateThrows_textAcceptedVariantsStillShip() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());

        PosterStyleValidationClient throwingClient = mock(PosterStyleValidationClient.class);
        when(throwingClient.validate(any(), any(), any()))
                .thenThrow(new IllegalStateException("style validation returned malformed JSON"));

        PosterOrchestrator.OrchestrationResult r = orchestratorWithStyleGate(
                new PosterStyleValidationService(throwingClient, true))
                .run(UUID.randomUUID(), req(), concept(), 1L, List.of(), null, null, null);

        assertThat(r.posters()).hasSize(3);
        assertThat(r.posters()).allSatisfy(p -> assertThat(p.status()).isEqualTo("COMPLETE"));
        verify(ideogram, never()).remix(any(), any(), anyInt(), anyLong(), any(), any(), any());
    }

    @Test
    void brandColors_forwardedToEveryGenerate() {
        // The text gate never passes, so each variant also sends both regenerates.
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{1}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textFail());

        BrandSnapshot brand = new BrandSnapshot(List.of("#ec4899", "#f6c04a"), null, false);
        orchestrator().run(UUID.randomUUID(), req(), concept(), 123L, List.of(), brand, null, null);

        verify(ideogram, times(9)).generate(any(), anyLong(), any(), any(),
                eq(List.of("#ec4899", "#f6c04a")), any());
    }

    @Test
    void noBrand_sendsEmptyPaletteToIdeogram() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

        orchestrator().run(UUID.randomUUID(), req(), concept(), 1L, List.of(), null, null, null);

        verify(ideogram, times(3)).generate(any(), anyLong(), any(), any(), eq(List.of()), any());
    }

    @Test
    void noBrand_skipsComposite_finalEqualsRaw() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

        PosterOrchestrator.OrchestrationResult r =
                orchestrator().run(UUID.randomUUID(), req(), concept(), 1L, List.of(), null, null, null);

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
        when(storage.writePng(any(), any())).thenAnswer(inv -> {
            byte[] b = inv.getArgument(0);
            return (b.length > 0 && b[0] == 7) ? "https://img/composited.png" : "https://img/raw.png";
        });
        when(logoCompositor.composite(any(), eq("https://cdn/logo.png")))
                .thenReturn(new byte[]{7});

        BrandSnapshot brand = new BrandSnapshot(java.util.List.of("#ec4899"), "https://cdn/logo.png", true);
        PosterOrchestrator.OrchestrationResult r = orchestrator().run(
                UUID.randomUUID(), req(), concept(), 123L, java.util.List.of(), brand, null, null);

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

    /**
     * poster-9: the logo composite must use the render bytes the process is still holding, not a
     * fresh HTTP GET of the object it just wrote. A transient R2 read error is a failure mode
     * holding the bytes cannot have — here download() always throws and the composite still lands.
     */
    @Test
    void logoComposite_usesTheRenderBytesInMemory_notAReDownload() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());
        when(storage.writePng(any(), any())).thenAnswer(inv -> {
            byte[] b = inv.getArgument(0);
            return (b.length > 0 && b[0] == 7) ? "https://img/composited.png" : "https://img/raw.png";
        });
        when(storage.download(anyString())).thenThrow(new RuntimeException("R2 read failed"));
        when(logoCompositor.composite(argThat(b -> b != null && b.length == 1 && b[0] == 2),
                eq("https://cdn/logo.png"))).thenReturn(new byte[]{7});

        BrandSnapshot brand = new BrandSnapshot(List.of("#ec4899"), "https://cdn/logo.png", true);
        PosterOrchestrator.OrchestrationResult r = orchestrator().run(
                UUID.randomUUID(), req(), concept(), 123L, List.of(), brand, null, null);

        assertThat(r.posters()).allSatisfy(p ->
                assertThat(p.finalUrl()).isEqualTo("https://img/composited.png"));
        verify(storage, never()).download(anyString());
    }

    @Test
    void compositeThrows_isIsolated_finalFallsBackToRaw_statusFailed() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());
        when(storage.writePng(any(), any())).thenReturn("https://img/raw.png");
        when(logoCompositor.composite(any(), any()))
                .thenThrow(new RuntimeException("decode boom"));

        BrandSnapshot brand = new BrandSnapshot(java.util.List.of("#ec4899"), "https://cdn/logo.png", true);
        PosterOrchestrator.OrchestrationResult r = orchestrator().run(
                UUID.randomUUID(), req(), concept(), 123L, java.util.List.of(), brand, null, null);

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

    // ── DJ photo tests ────────────────────────────────────────────────────────

    private static final DjPhotoSnapshot DJ =
            new DjPhotoSnapshot("https://cdn.example/dj.jpg", new byte[]{1, 2}, "image/jpeg");

    @Test
    void djModePassesCharacterRefToGenerate() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

        orchestrator().run(UUID.randomUUID(), req(), concept(), 7L, List.of(), null, DJ, null);

        ArgumentCaptor<StyleReferencePart> ref = ArgumentCaptor.forClass(StyleReferencePart.class);
        verify(ideogram, atLeastOnce()).generate(anyString(), anyLong(), anyList(), any(), anyList(), ref.capture());
        assertThat(ref.getValue()).isNotNull();
        assertThat(ref.getValue().filename()).isEqualTo("dj-photo.jpg");
    }

    /**
     * poster-16: poster_generations.organizer_id has existed since V3 with no writer, so every row
     * carried no actor for a paid AI render.
     */
    @Test
    void organizerIdIsStampedOnTheGeneration() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());
        UUID organizer = UUID.randomUUID();

        orchestrator().run(UUID.randomUUID(), req(), concept(), 7L, List.of(), null, null, organizer);

        ArgumentCaptor<PosterGeneration> saved = ArgumentCaptor.forClass(PosterGeneration.class);
        verify(repo, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getOrganizerId()).isEqualTo(organizer);
    }

    @Test
    void djPhotoUrlIsSnapshottedOnGeneration() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

        orchestrator().run(UUID.randomUUID(), req(), concept(), 7L, List.of(), null, DJ, null);

        ArgumentCaptor<PosterGeneration> saved = ArgumentCaptor.forClass(PosterGeneration.class);
        verify(repo, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getDjPhotoUrl()).isEqualTo("https://cdn.example/dj.jpg");
    }

    @Test
    void nullDjPhotoKeepsBaselineNullCharacterRef() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

        orchestrator().run(UUID.randomUUID(), req(), concept(), 7L, List.of(), null, null, null);

        verify(ideogram, atLeastOnce()).generate(anyString(), anyLong(), anyList(), any(), anyList(), isNull());
    }

    // ── DJ-mode palette regrade ───────────────────────────────────────────────

    @Test
    void djModeWithBrand_runsPaletteRegrade_withoutCharRef_withPalette() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(ideogram.remix(any(), any(), anyInt(), anyLong(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{5}, 2L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());
        when(storage.writePng(any(), any())).thenAnswer(inv -> {
            byte[] b = inv.getArgument(0);
            return (b.length > 0 && b[0] == 5) ? "https://img/regraded.png" : "https://img/raw.png";
        });

        BrandSnapshot brand = new BrandSnapshot(List.of("#ec4899", "#f6c04a"), null, false);
        PosterOrchestrator.OrchestrationResult r =
                orchestrator().run(UUID.randomUUID(), req(), concept(), 7L, List.of(), brand, DJ, null);

        // One regrade per variant: no char ref, palette attached, NO style refs/preset, weight 85.
        verify(ideogram, times(3)).remix(any(), contains("Re-grade"), eq(85), anyLong(),
                eq(List.of()), isNull(), eq(List.of("#ec4899", "#f6c04a")));
        assertThat(r.posters()).allSatisfy(p -> assertThat(p.rawUrl()).isEqualTo("https://img/regraded.png"));
    }

    @Test
    void paletteRegradeTextBreak_fallsBackToPreRegradeRender() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(ideogram.remix(any(), any(), anyInt(), anyLong(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{5}, 2L));
        // The original render {2} passes the text gate; the regrade {5} breaks it.
        when(textValidation.validateOrExplain(any(), any())).thenAnswer(inv -> {
            byte[] img = inv.getArgument(0);
            return (img.length > 0 && img[0] == 5) ? textFail() : textOk();
        });
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());
        when(storage.writePng(any(), any())).thenAnswer(inv -> {
            byte[] b = inv.getArgument(0);
            return (b.length > 0 && b[0] == 5) ? "https://img/regraded.png" : "https://img/raw.png";
        });

        BrandSnapshot brand = new BrandSnapshot(List.of("#ec4899"), null, false);
        PosterOrchestrator.OrchestrationResult r =
                orchestrator().run(UUID.randomUUID(), req(), concept(), 7L, List.of(), brand, DJ, null);

        assertThat(r.posters()).allSatisfy(p -> {
            assertThat(p.status()).isEqualTo("COMPLETE");
            assertThat(p.rawUrl()).isEqualTo("https://img/raw.png");
        });
    }

    @Test
    void paletteRegradeSkipped_withoutBrandColours_andInNonDjMode() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

        // DJ mode without brand colours → nothing to regrade with.
        orchestrator().run(UUID.randomUUID(), req(), concept(), 7L, List.of(), null, DJ, null);
        // Non-DJ mode with brand colours → palette already rode the generate call structurally.
        BrandSnapshot brand = new BrandSnapshot(List.of("#ec4899"), null, false);
        orchestrator().run(UUID.randomUUID(), req(), concept(), 8L, List.of(), brand, null, null);

        verify(ideogram, never()).remix(any(), any(), anyInt(), anyLong(), any(), any(), any());
    }

    @Test
    void paletteRegradeThrow_isIsolated_generationSucceeds() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{2}, 1L));
        when(ideogram.remix(any(), any(), anyInt(), anyLong(), any(), any(), any()))
                .thenThrow(new RuntimeException("ideogram 503"));
        when(textValidation.validateOrExplain(any(), any())).thenReturn(textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

        BrandSnapshot brand = new BrandSnapshot(List.of("#ec4899"), null, false);
        PosterOrchestrator.OrchestrationResult r =
                orchestrator().run(UUID.randomUUID(), req(), concept(), 7L, List.of(), brand, DJ, null);

        assertThat(r.posters()).allSatisfy(p -> assertThat(p.status()).isEqualTo("COMPLETE"));
    }

    private static PosterConcept singleVariantConcept() {
        return new PosterConcept("brutalist_techno", "colors", List.of(
                new PosterVariant("typographic", "typographic prompt with TITLE", "4:5", "Design")));
    }

    private static PosterTextValidationService.ValidationDecision textVerdict(
            List<String> missing, List<String> extra) {
        return new PosterTextValidationService.ValidationDecision(false, "gate", missing, extra);
    }

    // Catches: when every attempt fails the organizer gets the last render, not the one with fewest text errors.
    @Test
    void allAttemptsFail_shipsFewestErrorsRender_notTheLast() {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{10}, 1L))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{11}, 2L))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{12}, 3L));
        when(ideogram.remix(any(), any(), anyInt(), anyLong(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{11}, 2L))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{12}, 3L));
        when(textValidation.validateOrExplain(any(), any())).thenAnswer(inv -> {
            byte[] img = inv.getArgument(0);
            return switch (img[0]) {
                case 10 -> textVerdict(List.of("DATE"), List.of());
                case 11 -> textVerdict(List.of("DATE", "VENUE"), List.of("X"));
                default -> textVerdict(List.of("DATE"), List.of("X", "Y"));
            };
        });

        orchestrator().run(UUID.randomUUID(), req(), singleVariantConcept(), 1L, List.of(), null, null, null);

        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        verify(storage).writePng(stored.capture(), any());
        assertThat(stored.getValue()).containsExactly(10);
        ArgumentCaptor<PosterGeneration> saved = ArgumentCaptor.forClass(PosterGeneration.class);
        verify(repo, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getVariants().get(0).getValidationVerdict()).isEqualTo("BEST_EFFORT");
    }

    // ── retry routing, best-of and the journal ────────────────────────────────

    private static PosterTextValidationService.ValidationDecision unevaluated() {
        return new PosterTextValidationService.ValidationDecision(
                false, "text validation could not be evaluated: timeout", List.of(), List.of(), false);
    }

    private static List<String> n(int count, String word) {
        return java.util.Collections.nCopies(count, word);
    }

    /** Every render (generate or remix) returns bytes {callIndex} and records the seed it was sent. */
    private List<Long> rendersNumberedInCallOrder() {
        List<Long> seeds = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any())).thenAnswer(inv -> {
            seeds.add(inv.getArgument(1));
            return new IdeogramV3Client.IdeogramResult(new byte[]{(byte) (seeds.size() - 1)}, inv.getArgument(1));
        });
        when(ideogram.remix(any(), any(), anyInt(), anyLong(), any(), any(), any())).thenAnswer(inv -> {
            seeds.add(inv.getArgument(3));
            return new IdeogramV3Client.IdeogramResult(new byte[]{(byte) (seeds.size() - 1)}, inv.getArgument(3));
        });
        return seeds;
    }

    private PosterVariantEntity savedVariant() {
        ArgumentCaptor<PosterGeneration> saved = ArgumentCaptor.forClass(PosterGeneration.class);
        verify(repo, atLeastOnce()).save(saved.capture());
        return saved.getValue().getVariants().get(0);
    }

    private static com.fasterxml.jackson.databind.JsonNode journal(PosterVariantEntity v) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(v.getValidationAttemptsJson());
    }

    private static List<Integer> shippedIndexes(com.fasterxml.jackson.databind.JsonNode journal) {
        List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < journal.size(); i++) {
            if (journal.get(i).path("shipped").asBoolean(false)) out.add(i);
        }
        return out;
    }

    // Catches: a text failure "fixed" by a remix that keeps the broken layout and drops the date (prod 2026-10-12).
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("textFailures")
    void everyTextFailure_isRegeneratedFromTheOriginalPrompt_neverRemixed(
            String name, PosterTextValidationService.ValidationDecision failure) throws Exception {
        List<Long> seeds = rendersNumberedInCallOrder();
        when(textValidation.validateOrExplain(any(), any())).thenAnswer(inv ->
                ((byte[]) inv.getArgument(0))[0] == 0 ? failure : textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

        orchestrator().run(UUID.randomUUID(), req(), singleVariantConcept(), 1L, List.of(), null, null, null);

        verify(ideogram, never()).remix(any(), any(), anyInt(), anyLong(), any(), any(), any());
        verify(ideogram, times(2)).generate(eq("typographic prompt with TITLE"), anyLong(), any(), any(), any(), any());
        assertThat(seeds).hasSize(2);
        assertThat(seeds.get(1)).isNotEqualTo(seeds.get(0));
        PosterVariantEntity v = savedVariant();
        com.fasterxml.jackson.databind.JsonNode j = journal(v);
        assertThat(List.of(j.get(0).path("mode").asText(), j.get(1).path("mode").asText()))
                .containsExactly("generate", "regenerate");
        assertThat(v.getValidationVerdict()).isEqualTo("ACCEPTED");
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> textFailures() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("missing",
                        textVerdict(List.of("31 OCT 2026"), List.of())),
                org.junit.jupiter.params.provider.Arguments.of("extra only",
                        textVerdict(List.of(), List.of("31 OCT"))),
                org.junit.jupiter.params.provider.Arguments.of("unreadable marks",
                        textVerdict(List.of(), List.of("[fine print, bottom-left]"))),
                org.junit.jupiter.params.provider.Arguments.of("unevaluated", unevaluated()));
    }

    // Catches: a dateless poster ships over one with a stray word, or the stored seed does not match the
    // shipped image. Each case orders the losing renders so that "ship the last" or "ship the first" fails.
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("bestOfCases")
    void bestOf_tieOnTotal_prefersFewerMissing_andShipsItsSeed(
            String name, List<int[]> missingExtraPerAttempt, int expectedWinner) throws Exception {
        List<Long> seeds = rendersNumberedInCallOrder();
        when(textValidation.validateOrExplain(any(), any())).thenAnswer(inv -> {
            int[] me = missingExtraPerAttempt.get(((byte[]) inv.getArgument(0))[0]);
            return textVerdict(n(me[0], "25 SEP 2026"), n(me[1], "WEVIKA"));
        });

        orchestrator().run(UUID.randomUUID(), req(), singleVariantConcept(), 1L, List.of(), null, null, null);

        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        verify(storage).writePng(stored.capture(), any());
        assertThat(stored.getValue()).containsExactly(expectedWinner);
        PosterVariantEntity v = savedVariant();
        assertThat(v.getValidationVerdict()).isEqualTo("BEST_EFFORT");
        assertThat(v.getSeed()).isEqualTo(seeds.get(expectedWinner));
        assertThat(shippedIndexes(journal(v))).containsExactly(expectedWinner);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> bestOfCases() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("equal total, fewer missing wins",
                        List.of(new int[]{1, 0}, new int[]{0, 1}, new int[]{1, 1}), 1),
                org.junit.jupiter.params.provider.Arguments.of("fully equal, earliest wins",
                        List.of(new int[]{0, 1}, new int[]{0, 1}, new int[]{0, 2}), 0));
    }

    // Catches: an unchecked render (0 missing / 0 extra because nothing was read) ships over a checked one.
    @Test
    void unevaluatedAttempt_neverBeatsAnEvaluatedFailure() {
        rendersNumberedInCallOrder();
        when(textValidation.validateOrExplain(any(), any())).thenAnswer(inv -> switch (((byte[]) inv.getArgument(0))[0]) {
            case 0 -> textVerdict(List.of("25 SEP 2026"), List.of("X"));
            case 1 -> textVerdict(List.of("25 SEP 2026"), List.of("X", "Y"));
            default -> unevaluated();
        });

        orchestrator().run(UUID.randomUUID(), req(), singleVariantConcept(), 1L, List.of(), null, null, null);

        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        verify(storage).writePng(stored.capture(), any());
        assertThat(stored.getValue()).containsExactly(0);
    }

    // Catches: a 503 on the last retry throws away two paid renders and fails the variant.
    @Test
    void retryRenderThrows_shipsBestSoFar_asBestEffort() throws Exception {
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{0}, 1L))
                .thenReturn(new IdeogramV3Client.IdeogramResult(new byte[]{1}, 2L))
                .thenThrow(new IllegalStateException("ideogram 503"));
        when(textValidation.validateOrExplain(any(), any())).thenAnswer(inv ->
                ((byte[]) inv.getArgument(0))[0] == 0
                        ? textVerdict(List.of("25 SEP 2026"), List.of())
                        : textVerdict(List.of("25 SEP 2026"), List.of("X")));

        PosterOrchestrator.OrchestrationResult r =
                orchestrator().run(UUID.randomUUID(), req(), singleVariantConcept(), 1L, List.of(), null, null, null);

        assertThat(r.posters()).singleElement().satisfies(p -> assertThat(p.status()).isEqualTo("COMPLETE"));
        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        verify(storage).writePng(stored.capture(), any());
        assertThat(stored.getValue()).containsExactly(0);
        PosterVariantEntity v = savedVariant();
        assertThat(v.getValidationVerdict()).isEqualTo("BEST_EFFORT");
        com.fasterxml.jackson.databind.JsonNode j = journal(v);
        assertThat(j).hasSize(3);
        assertThat(j.get(2).path("render").asText()).isEqualTo("failed");
        assertThat(j.get(2).path("text").path("evaluated").asBoolean(true)).isFalse();
        assertThat(j.get(2).path("text").path("reason").asText()).contains("ideogram 503");
        assertThat(shippedIndexes(j)).containsExactly(0);
    }

    // Catches: a DJ poster loses the DJ's face when missing text is regenerated.
    @Test
    void djMode_missingText_regenerateCarriesCharacterRef() {
        rendersNumberedInCallOrder();
        when(textValidation.validateOrExplain(any(), any())).thenAnswer(inv ->
                ((byte[]) inv.getArgument(0))[0] == 0 ? textVerdict(List.of("25 SEP 2026"), List.of()) : textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

        orchestrator().run(UUID.randomUUID(), req(), singleVariantConcept(), 7L, List.of(), null, DJ, null);

        verify(ideogram, never()).remix(any(), any(), anyInt(), anyLong(), any(), any(), any());
        verify(ideogram, times(2)).generate(anyString(), anyLong(), anyList(), any(), anyList(),
                argThat(ref -> ref != null && "dj-photo.jpg".equals(ref.filename())));
    }

    // Catches: an auditor cannot tell which attempt shipped, or why a retry was a fresh render.
    @Test
    void journal_marksShippedEntry_andRecordsRegenerateMode() throws Exception {
        List<Long> seeds = rendersNumberedInCallOrder();
        when(textValidation.validateOrExplain(any(), any())).thenAnswer(inv ->
                ((byte[]) inv.getArgument(0))[0] == 0 ? textVerdict(List.of("25 SEP 2026"), List.of()) : textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

        orchestrator().run(UUID.randomUUID(), req(), singleVariantConcept(), 1L, List.of(), null, null, null);

        PosterVariantEntity v = savedVariant();
        com.fasterxml.jackson.databind.JsonNode j = journal(v);
        assertThat(j).hasSize(2);
        assertThat(j.get(0).path("mode").asText()).isEqualTo("generate");
        assertThat(j.get(0).path("text").path("evaluated").asBoolean(false)).isTrue();
        assertThat(j.get(0).path("text").path("missing").asInt(-1)).isEqualTo(1);
        assertThat(j.get(0).path("text").path("extra").asInt(-1)).isEqualTo(0);
        assertThat(j.get(1).path("mode").asText()).isEqualTo("regenerate");
        assertThat(j.get(1).path("seed").asLong()).isEqualTo(seeds.get(1));
        assertThat(shippedIndexes(j)).containsExactly(1);
        assertThat(v.getSeed()).isEqualTo(seeds.get(1));
    }

    // Catches: the journal or the stored seed pointing at the pre-regrade render when the regraded
    // one shipped (or the reverse), so the shipped image cannot be traced or reproduced.
    @org.junit.jupiter.params.ParameterizedTest(name = "regrade kept: {0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void paletteRegrade_marksOnlyTheShippedEntry_andStoresItsSeed(boolean regradeKept) throws Exception {
        List<Long> seeds = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(ideogram.generate(any(), anyLong(), any(), any(), any(), any())).thenAnswer(inv -> {
            seeds.add(inv.getArgument(1));
            return new IdeogramV3Client.IdeogramResult(new byte[]{2}, inv.getArgument(1));
        });
        when(ideogram.remix(any(), any(), anyInt(), anyLong(), any(), any(), any())).thenAnswer(inv -> {
            seeds.add(inv.getArgument(3));
            return new IdeogramV3Client.IdeogramResult(new byte[]{5}, inv.getArgument(3));
        });
        when(textValidation.validateOrExplain(any(), any())).thenAnswer(inv ->
                ((byte[]) inv.getArgument(0))[0] == 5 && !regradeKept ? textFail() : textOk());
        when(styleValidation.validateOrExplain(any(), any(), any())).thenReturn(styleOk());

        BrandSnapshot brand = new BrandSnapshot(List.of("#ec4899"), null, false);
        orchestrator().run(UUID.randomUUID(), req(), singleVariantConcept(), 7L, List.of(), brand, DJ, null);

        PosterVariantEntity v = savedVariant();
        com.fasterxml.jackson.databind.JsonNode j = journal(v);
        List<Integer> shipped = shippedIndexes(j);
        assertThat(shipped).hasSize(1);
        assertThat(j.get(shipped.get(0)).path("mode").asText()).isEqualTo(regradeKept ? "palette-regrade" : "generate");
        assertThat(v.getSeed()).isEqualTo(seeds.get(regradeKept ? 1 : 0));
    }
}

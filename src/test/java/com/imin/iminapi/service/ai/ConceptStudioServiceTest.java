package com.imin.iminapi.service.ai;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.GeneratedPoster;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PosterVariant;
import com.imin.iminapi.dto.Vibe;
import com.imin.iminapi.dto.PricingRecommendation;
import com.imin.iminapi.dto.ai.ConceptOverview;
import com.imin.iminapi.dto.ai.ConceptRequest;
import com.imin.iminapi.dto.ai.ConceptResponse;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.model.GeneratedEventStatus;
import com.imin.iminapi.model.ImageProvider;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.GeneratedEventRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.AiEventDescriptionService;
import com.imin.iminapi.service.PricingService;
import com.imin.iminapi.service.poster.PosterOrchestrator;
import com.imin.iminapi.service.poster.PosterOrchestrator.OrchestrationResult;
import com.imin.iminapi.security.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ConceptStudioServiceTest {

    AiEventDescriptionService descService = mock(AiEventDescriptionService.class);
    PosterOrchestrator orchestrator = mock(PosterOrchestrator.class);
    PricingService pricing = mock(PricingService.class);
    ConceptOverviewLlm overviewLlm = mock(ConceptOverviewLlm.class);
    GeneratedEventRepository repo = mock(GeneratedEventRepository.class);
    com.imin.iminapi.service.poster.VibeLibrary vibeLibrary =
            mock(com.imin.iminapi.service.poster.VibeLibrary.class);

    ConceptStudioService sut = new ConceptStudioService(
            descService, orchestrator, pricing, overviewLlm, repo, vibeLibrary);

    private AuthPrincipal owner() {
        return new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), UserRole.OWNER, UUID.randomUUID());
    }

    @Test
    void create_runs_pipeline_and_maps_to_concept_response() {
        AuthPrincipal p = owner();
        when(repo.save(any(GeneratedEvent.class))).thenAnswer(inv -> {
            GeneratedEvent e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });

        PosterConcept concept = new PosterConcept("neon_underground", "Deep magenta and electric blue",
                List.of(new PosterVariant("atmospheric", "Large event prompt text here one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty twenty-one twenty-two twenty-three twenty-four twenty-five twenty-six twenty-seven twenty-eight twenty-nine thirty more words here", "3:4", "Design"),
                        new PosterVariant("graphic",     "Large event prompt text here one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty twenty-one twenty-two twenty-three twenty-four twenty-five twenty-six twenty-seven twenty-eight twenty-nine thirty more words here", "1:1", "Design"),
                        new PosterVariant("minimal",     "Large event prompt text here one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty twenty-one twenty-two twenty-three twenty-four twenty-five twenty-six twenty-seven twenty-eight twenty-nine thirty more words here", "4:5", "Design")));
        when(descService.generateConcept(any(), anyLong()))
                .thenReturn(new AiEventDescriptionService.GeneratedConcept(concept, List.of()));

        OrchestrationResult result = new OrchestrationResult(
                UUID.randomUUID(), "neon_underground",
                List.of(
                        new GeneratedPoster(UUID.randomUUID(), "people", "https://cdn/raw1.png", "https://cdn/p1.png", 1L, "prompt", List.of(), Map.of(), "COMPLETE", null),
                        new GeneratedPoster(UUID.randomUUID(), "object",     "https://cdn/raw2.png", "https://cdn/p2.png", 2L, "prompt", List.of(), Map.of(), "COMPLETE", null),
                        new GeneratedPoster(UUID.randomUUID(), "typographic",     "https://cdn/raw3.png", "https://cdn/p3.png", 3L, "prompt", List.of(), Map.of(), "COMPLETE", null)));
        when(orchestrator.run(any(), any(), any(), anyLong(), any())).thenReturn(result);

        when(pricing.recommend(any(), any(), any())).thenReturn(
                new PricingRecommendation(new BigDecimal("12.00"), new BigDecimal("24.00"), "ok"));

        when(overviewLlm.generate(any(), any())).thenReturn(
                new ConceptOverview("ANTRUM", "Deep in a Berlin warehouse...",
                        List.of("#1a1a18", "#2d5cff", "#c03030", "#f2f1ec"), 250, 78));

        ConceptResponse r = sut.create(p, new ConceptRequest(
                "Moody Berlin techno warehouse", "Techno", "Berlin", null, null,
                null, null, null, null, null, null));

        assertThat(r.conceptId()).isNotNull();
        assertThat(r.name()).isEqualTo("ANTRUM");
        assertThat(r.description()).startsWith("Deep");
        assertThat(r.posters()).hasSize(3);
        assertThat(r.posters().get(0).url()).isEqualTo("https://cdn/p1.png");
        assertThat(r.palette()).containsExactly("#1a1a18", "#2d5cff", "#c03030", "#f2f1ec");
        assertThat(r.suggestedTiers()).extracting("name").containsExactly("Early Bird", "Standard", "Door");
        assertThat(r.suggestedTiers().get(0).priceMinor()).isEqualTo(1200);
        assertThat(r.suggestedTiers().get(2).priceMinor()).isEqualTo(2400);
        assertThat(r.suggestedCapacity()).isEqualTo(250);
        assertThat(r.confidencePct()).isEqualTo(78);
    }

    @Test
    void regenerate_loads_existing_concept_and_reruns_pipeline() {
        AuthPrincipal p = owner();
        UUID conceptId = UUID.randomUUID();
        GeneratedEvent existing = new GeneratedEvent();
        existing.setId(conceptId); existing.setOrgId(p.orgId());
        existing.setVibe("Old vibe text"); existing.setGenre("House"); existing.setCity("Paris");
        existing.setStatus(GeneratedEventStatus.COMPLETE);
        when(repo.findByIdAndOrgId(conceptId, p.orgId())).thenReturn(java.util.Optional.of(existing));
        when(repo.save(any(GeneratedEvent.class))).thenAnswer(inv -> {
            GeneratedEvent e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });

        // Reuse same mocks as the happy-path test
        PosterConcept concept = new PosterConcept("flat_graphic", "Bold flat blocks",
                List.of(new PosterVariant("atmospheric", "Large event prompt text here one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty twenty-one twenty-two twenty-three twenty-four twenty-five twenty-six twenty-seven twenty-eight twenty-nine thirty more words here", "3:4", "Design"),
                        new PosterVariant("graphic", "Large event prompt text here one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty twenty-one twenty-two twenty-three twenty-four twenty-five twenty-six twenty-seven twenty-eight twenty-nine thirty more words here", "1:1", "Design"),
                        new PosterVariant("minimal", "Large event prompt text here one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty twenty-one twenty-two twenty-three twenty-four twenty-five twenty-six twenty-seven twenty-eight twenty-nine thirty more words here", "4:5", "Design")));
        when(descService.generateConcept(any(), anyLong()))
                .thenReturn(new AiEventDescriptionService.GeneratedConcept(concept, List.of()));
        when(orchestrator.run(any(), any(), any(), anyLong(), any())).thenReturn(new OrchestrationResult(
                UUID.randomUUID(), "flat_graphic",
                List.of(new GeneratedPoster(UUID.randomUUID(), "people", "raw", "url1", 1L, "p", List.of(), Map.of(), "COMPLETE", null),
                        new GeneratedPoster(UUID.randomUUID(), "object",     "raw", "url2", 2L, "p", List.of(), Map.of(), "COMPLETE", null),
                        new GeneratedPoster(UUID.randomUUID(), "typographic",     "raw", "url3", 3L, "p", List.of(), Map.of(), "COMPLETE", null))));
        when(pricing.recommend(any(), any(), any())).thenReturn(
                new PricingRecommendation(new BigDecimal("10.00"), new BigDecimal("20.00"), "ok"));
        when(overviewLlm.generate(any(), any())).thenReturn(new ConceptOverview(
                "NEW NAME", "new desc", List.of("#000"), 200, 80));

        ConceptResponse r = sut.regenerate(p, conceptId, java.util.List.of());
        assertThat(r.conceptId()).isNotEqualTo(conceptId); // a fresh row
        assertThat(r.name()).isEqualTo("NEW NAME");
    }

    @Test
    void create_withVibeId_pins_vibe_as_concept_style_tag() {
        AuthPrincipal p = owner();
        when(repo.save(any(GeneratedEvent.class))).thenAnswer(inv -> {
            GeneratedEvent e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
        when(vibeLibrary.hasVibe("brutalist_techno")).thenReturn(true);
        when(vibeLibrary.byId("brutalist_techno")).thenReturn(java.util.Optional.of(new Vibe(
                "brutalist_techno", "Brutalist Techno", List.of("techno"), "vs", List.of("#000"),
                "typ", "comp", List.of(), List.of(), "recraft", List.of(), null, "brutalist", false,
                "subject", com.imin.iminapi.dto.StyleMode.CURATED_SUBSTYLE, "urban_drama")));

        String body = "p".repeat(40);
        when(descService.generateConcept(any(), anyLong())).thenReturn(new AiEventDescriptionService.GeneratedConcept(
                new PosterConcept("neon_underground", "palette",
                        List.of(new PosterVariant("people", body, "4:5", "Design"),
                                new PosterVariant("object", body, "1:1", "Design"),
                                new PosterVariant("typographic", body, "9:16", "Design"))),
                List.of()));
        when(orchestrator.run(any(), any(), any(), anyLong(), any())).thenReturn(new OrchestrationResult(
                UUID.randomUUID(), "brutalist_techno",
                List.of(new GeneratedPoster(UUID.randomUUID(), "people", "raw", "u1", 1L, "p", List.of(), Map.of(), "COMPLETE", null))));
        when(pricing.recommend(any(), any(), any())).thenReturn(
                new PricingRecommendation(new BigDecimal("12.00"), new BigDecimal("24.00"), "ok"));
        when(overviewLlm.generate(any(), any())).thenReturn(
                new ConceptOverview("N", "d", List.of("#000"), 100, 50));

        sut.create(p, new ConceptRequest(
                "Brutalist warehouse rave brief", "Techno", "Berlin", null, "brutalist_techno",
                null, null, null, null, null, null));

        // The concept handed to the orchestrator carries the pinned vibe id, not the LLM's echo.
        ArgumentCaptor<PosterConcept> cap = ArgumentCaptor.forClass(PosterConcept.class);
        verify(orchestrator).run(any(), any(), cap.capture(), anyLong(), any());
        assertThat(cap.getValue().subStyleTag()).isEqualTo("brutalist_techno");
    }

    @Test
    void providerFor_defaultsToRecraftForReferenceFirstGeneration() {
        Vibe recraftVibe = vibe("recraft");
        assertThat(sut.providerFor(recraftVibe)).isEqualTo(ImageProvider.RECRAFT);
        assertThat(sut.providerFor(vibe("sdxl"))).isEqualTo(ImageProvider.RECRAFT);
        assertThat(sut.providerFor(null)).isEqualTo(ImageProvider.RECRAFT);
    }

    @Test
    void providerFor_mapsModelRoute_whenRoutingEnabled() {
        org.springframework.test.util.ReflectionTestUtils.setField(sut, "providerRoutingEnabled", true);
        assertThat(sut.providerFor(vibe("recraft"))).isEqualTo(ImageProvider.RECRAFT);
        assertThat(sut.providerFor(vibe("gpt-image"))).isEqualTo(ImageProvider.OPENAI);
        assertThat(sut.providerFor(vibe("replicate"))).isEqualTo(ImageProvider.REPLICATE);
        assertThat(sut.providerFor(vibe("sdxl"))).isEqualTo(ImageProvider.RECRAFT);
        assertThat(sut.providerFor(null)).isEqualTo(ImageProvider.RECRAFT);
    }

    @Test
    void eventCreatorRequest_defaultProviderIsRecraft() {
        EventCreatorRequest request = new EventCreatorRequest(
                "vibe", "tone", "techno", "Berlin",
                LocalDate.of(2026, 6, 14), List.of("INSTAGRAM"),
                null, null, "Void", null, null, null,
                "brutalist_techno", null);

        assertThat(request.effectiveImageProvider()).isEqualTo(ImageProvider.RECRAFT);
    }

    private static Vibe vibe(String modelRoute) {
        return new Vibe("v", "V", List.of("g"), "vs", List.of("#000"), "typ", "comp",
                List.of(), List.of(), modelRoute, List.of(), null, "tpl", false,
                "subject", com.imin.iminapi.dto.StyleMode.TRAINED_STYLE_ID, null);
    }

    @Test
    void create_withUnknownVibeId_throws() {
        AuthPrincipal p = owner();
        when(vibeLibrary.hasVibe("not_a_vibe")).thenReturn(false);

        assertThatThrownBy(() -> sut.create(p,
                new ConceptRequest("Some brief text long enough", null, null, null, "not_a_vibe",
                        null, null, null, null, null, null)))
                .isInstanceOf(ApiException.class);
    }
}

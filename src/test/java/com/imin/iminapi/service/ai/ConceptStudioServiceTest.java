package com.imin.iminapi.service.ai;

import com.imin.iminapi.dto.GeneratedPoster;
import com.imin.iminapi.dto.PosterConcept;
import com.imin.iminapi.dto.PosterVariant;
import com.imin.iminapi.dto.PricingRecommendation;
import com.imin.iminapi.dto.ai.ConceptOverview;
import com.imin.iminapi.dto.ai.ConceptRequest;
import com.imin.iminapi.dto.ai.ConceptResponse;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.model.GeneratedEventStatus;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.GeneratedEventRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.AiEventDescriptionService;
import com.imin.iminapi.service.PricingService;
import com.imin.iminapi.service.poster.PosterOrchestrator;
import com.imin.iminapi.service.poster.PosterOrchestrator.OrchestrationResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConceptStudioServiceTest {

    AiEventDescriptionService descService = mock(AiEventDescriptionService.class);
    PosterOrchestrator orchestrator = mock(PosterOrchestrator.class);
    PricingService pricing = mock(PricingService.class);
    ConceptOverviewLlm overviewLlm = mock(ConceptOverviewLlm.class);
    GeneratedEventRepository repo = mock(GeneratedEventRepository.class);

    ConceptStudioService sut = new ConceptStudioService(
            descService, orchestrator, pricing, overviewLlm, repo);

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
        when(descService.generateConcept(any())).thenReturn(concept);

        OrchestrationResult result = new OrchestrationResult(
                UUID.randomUUID(), "neon_underground",
                List.of(
                        new GeneratedPoster(UUID.randomUUID(), "atmospheric", "https://cdn/raw1.png", "https://cdn/p1.png", 1L, "prompt", List.of(), Map.of(), "COMPLETE", null),
                        new GeneratedPoster(UUID.randomUUID(), "graphic",     "https://cdn/raw2.png", "https://cdn/p2.png", 2L, "prompt", List.of(), Map.of(), "COMPLETE", null),
                        new GeneratedPoster(UUID.randomUUID(), "minimal",     "https://cdn/raw3.png", "https://cdn/p3.png", 3L, "prompt", List.of(), Map.of(), "COMPLETE", null)));
        when(orchestrator.run(any(), any(), any())).thenReturn(result);

        when(pricing.recommend(any(), any(), any())).thenReturn(
                new PricingRecommendation(new BigDecimal("12.00"), new BigDecimal("24.00"), "ok"));

        when(overviewLlm.generate(any(), any())).thenReturn(
                new ConceptOverview("ANTRUM", "Deep in a Berlin warehouse...",
                        List.of("#1a1a18", "#2d5cff", "#c03030", "#f2f1ec"), 250, 78));

        ConceptResponse r = sut.create(p, new ConceptRequest("Moody Berlin techno warehouse", "Techno", "Berlin", null));

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
        when(descService.generateConcept(any())).thenReturn(concept);
        when(orchestrator.run(any(), any(), any())).thenReturn(new OrchestrationResult(
                UUID.randomUUID(), "flat_graphic",
                List.of(new GeneratedPoster(UUID.randomUUID(), "atmospheric", "raw", "url1", 1L, "p", List.of(), Map.of(), "COMPLETE", null),
                        new GeneratedPoster(UUID.randomUUID(), "graphic",     "raw", "url2", 2L, "p", List.of(), Map.of(), "COMPLETE", null),
                        new GeneratedPoster(UUID.randomUUID(), "minimal",     "raw", "url3", 3L, "p", List.of(), Map.of(), "COMPLETE", null))));
        when(pricing.recommend(any(), any(), any())).thenReturn(
                new PricingRecommendation(new BigDecimal("10.00"), new BigDecimal("20.00"), "ok"));
        when(overviewLlm.generate(any(), any())).thenReturn(new ConceptOverview(
                "NEW NAME", "new desc", List.of("#000"), 200, 80));

        ConceptResponse r = sut.regenerate(p, conceptId, java.util.List.of());
        assertThat(r.conceptId()).isNotEqualTo(conceptId); // a fresh row
        assertThat(r.name()).isEqualTo("NEW NAME");
    }
}

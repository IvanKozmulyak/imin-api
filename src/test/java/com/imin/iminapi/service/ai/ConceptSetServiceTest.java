package com.imin.iminapi.service.ai;

import com.imin.iminapi.dto.PricingRecommendation;
import com.imin.iminapi.dto.ai.ConceptSet;
import com.imin.iminapi.dto.ai.ConceptSetRequest;
import com.imin.iminapi.dto.ai.ConceptSetResponse;
import com.imin.iminapi.model.Concept;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.model.GeneratedEventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.GeneratedEventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.PricingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConceptSetServiceTest {

    // Deep stubs cover the fluent chain chat.prompt().user(...).call().entity(ConceptSet.class).
    org.springframework.ai.chat.client.ChatClient chat =
            mock(org.springframework.ai.chat.client.ChatClient.class, RETURNS_DEEP_STUBS);
    OrganizationRepository orgs = mock(OrganizationRepository.class);
    PricingService pricing = mock(PricingService.class);
    GeneratedEventRepository repo = mock(GeneratedEventRepository.class);

    ConceptSetService sut = new ConceptSetService(chat, orgs, pricing, repo);

    private AuthPrincipal principal() {
        return new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), UserRole.OWNER, UUID.randomUUID());
    }

    /** Stub the ChatClient chain to return the given set, plus pricing + repo flush behaviour. */
    private void stub(ConceptSet set) {
        when(chat.prompt().user(anyString()).call().entity(ConceptSet.class)).thenReturn(set);
        when(pricing.recommend(any(), any(), any())).thenReturn(
                new PricingRecommendation(new BigDecimal("12.00"), new BigDecimal("24.00"), "ok"));
        when(orgs.findById(any())).thenReturn(Optional.empty());
        // Emulate JPA assigning generated ids to the cascade-persisted Concept rows on flush.
        when(repo.saveAndFlush(any(GeneratedEvent.class))).thenAnswer(inv -> {
            GeneratedEvent e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            for (Concept c : e.getConcepts()) {
                if (c.getId() == null) c.setId(UUID.randomUUID());
            }
            return e;
        });
    }

    private static ConceptSet.LlmConcept concept(String name) {
        return new ConceptSet.LlmConcept(name, "A 40-word second-person description of the night.",
                new ConceptSet.LlmCaptions("ig copy #techno", "tiktok copy", "x copy"),
                "Techno", "Rave", 200);
    }

    @Test
    void returnsThreeConceptsWithCaptionsAndTiers() {
        stub(new ConceptSet(List.of(
                concept("Warehouse Mass"), concept("Concrete Hours"), concept("After Hours Mass"))));
        AuthPrincipal p = principal();

        ConceptSetResponse res = sut.create(p, new ConceptSetRequest(
                "Moody Paris techno in a raw warehouse, 200 people, Saturday night", "Techno", "Paris", 200));

        assertThat(res.concepts()).hasSize(3);
        assertThat(res.generatedEventId()).isNotNull();
        assertThat(res.concepts().get(0).name()).isEqualTo("Warehouse Mass");
        assertThat(res.concepts().get(0).conceptId()).isNotNull();
        assertThat(res.concepts().get(0).captions().instagram()).isEqualTo("ig copy #techno");
        assertThat(res.concepts().get(0).captions().tiktok()).isEqualTo("tiktok copy");
        assertThat(res.concepts().get(0).captions().x()).isEqualTo("x copy");
        assertThat(res.concepts().get(0).suggestedGenre()).isEqualTo("Techno");
        assertThat(res.concepts().get(0).suggestedType()).isEqualTo("Rave");
        assertThat(res.concepts().get(0).suggestedCapacity()).isEqualTo(200);
        assertThat(res.concepts().get(0).suggestedTiers()).extracting("name")
                .containsExactly("Early Bird", "Standard", "Door");
        assertThat(res.concepts().get(0).suggestedTiers().get(0).priceMinor()).isEqualTo(1200);
        assertThat(res.concepts().get(0).suggestedTiers().get(2).priceMinor()).isEqualTo(2400);
        // Distinct persisted ids per card.
        assertThat(res.concepts()).extracting("conceptId").doesNotContainNull().doesNotHaveDuplicates();
    }

    @Test
    void persistsThreeConceptRowsWithCaptionsAndCompletesStaging() {
        stub(new ConceptSet(List.of(
                concept("One"), concept("Two"), concept("Three"))));

        sut.create(principal(), new ConceptSetRequest(
                "Moody Paris techno in a raw warehouse, 200 people, Saturday night", "Techno", "Paris", 200));

        ArgumentCaptor<GeneratedEvent> cap = ArgumentCaptor.forClass(GeneratedEvent.class);
        verify(repo).saveAndFlush(cap.capture());
        GeneratedEvent saved = cap.getValue();
        assertThat(saved.getStatus()).isEqualTo(GeneratedEventStatus.COMPLETE);
        assertThat(saved.getConcepts()).hasSize(3);
        assertThat(saved.getConcepts().get(0).getInstagramCaption()).isEqualTo("ig copy #techno");
        assertThat(saved.getConcepts().get(0).getTiktokCaption()).isEqualTo("tiktok copy");
        assertThat(saved.getConcepts().get(0).getXCaption()).isEqualTo("x copy");
        assertThat(saved.getConcepts().get(0).getTitle()).isEqualTo("One");
        assertThat(saved.getConcepts().get(2).getSortOrder()).isEqualTo(2);
    }

    @Test
    void truncatesToThreeWhenModelReturnsMore() {
        stub(new ConceptSet(List.of(
                concept("One"), concept("Two"), concept("Three"), concept("Four"))));

        ConceptSetResponse res = sut.create(principal(), new ConceptSetRequest(
                "Moody Paris techno in a raw warehouse, 200 people", null, null, null));

        assertThat(res.concepts()).hasSize(3);
    }

    @Test
    void brandConditioningDoesNotThrowWhenOrgHasBrand() {
        stub(new ConceptSet(List.of(concept("One"), concept("Two"), concept("Three"))));
        Organization o = new Organization();
        o.setBrandName("NocturneClub");
        o.setBrandAccentColors(new java.util.ArrayList<>(List.of("#ec4899", "#a78bfa")));
        when(orgs.findById(any())).thenReturn(Optional.of(o));

        ConceptSetResponse res = sut.create(principal(), new ConceptSetRequest(
                "Moody Paris techno in a raw warehouse, 200 people", "Techno", "Paris", 200));

        assertThat(res.concepts()).hasSize(3);
    }

    @Test
    void brandLookupFailureProceedsBrandless() {
        stub(new ConceptSet(List.of(concept("One"), concept("Two"), concept("Three"))));
        when(orgs.findById(any())).thenThrow(new RuntimeException("db hiccup"));

        ConceptSetResponse res = sut.create(principal(), new ConceptSetRequest(
                "Moody Paris techno in a raw warehouse, 200 people", "Techno", "Paris", 200));

        assertThat(res.concepts()).hasSize(3);
    }

    @Test
    void throwsUpstreamUnavailableWhenModelKeepsFailing() {
        when(orgs.findById(any())).thenReturn(Optional.empty());
        when(chat.prompt().user(anyString()).call().entity(ConceptSet.class))
                .thenThrow(new RuntimeException("upstream down"));

        assertThatThrownBy(() -> sut.create(principal(), new ConceptSetRequest(
                "Moody Paris techno in a raw warehouse, 200 people", "Techno", "Paris", 200)))
                .isInstanceOf(ApiException.class);
    }
}

package com.imin.iminapi.controller.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dto.ai.ConceptSet;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.model.GeneratedEventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.AiGenerationUsageRepository;
import com.imin.iminapi.repository.GeneratedEventRepository;
import com.imin.iminapi.service.poster.IdeogramV3Client;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Prompt-bound fields are rejected before any paid LLM or image call; another org's concept is a no-leak 404. */
@IminIntegrationTest
class ConceptControllerTest {

    private static final String VIBE = "Moody Berlin techno warehouse vibe";

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired GeneratedEventRepository generated;
    @Autowired ChatClient chatClient;
    @Autowired IdeogramV3Client ideogram;
    @Autowired AiGenerationUsageRepository usage;

    private final ObjectMapper om = new ObjectMapper();

    private RequestPostProcessor as(User u) {
        return authentication(new UsernamePasswordAuthenticationToken(
                fx.principal(u), null, List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()))));
    }

    static Stream<Arguments> unboundedBodies() {
        return Stream.of(
                Arguments.of("/api/v1/ai/events/concept", Map.of("vibe", "short"), "vibe"),
                Arguments.of("/api/v1/ai/events/concept",
                        Map.of("vibe", VIBE, "lineup", Collections.nCopies(200, "DJ")), "lineup"),
                Arguments.of("/api/v1/ai/events/concept",
                        Map.of("vibe", VIBE, "lineup", List.of("x".repeat(300_000))), null),
                Arguments.of("/api/v1/ai/events/concept", Map.of("vibe", VIBE, "venue", "v".repeat(50_000)), "venue"),
                Arguments.of("/api/v1/ai/events/concepts", Map.of("vibe", "short"), "vibe"));
    }

    /** `lineup` and the free-text fields are joined into the paid prompt; the bucket and quota run after binding. */
    @ParameterizedTest
    @MethodSource("unboundedBodies")
    void an_out_of_bounds_body_is_FIELD_INVALID_before_any_paid_call(String path, Map<String, Object> body,
                                                                     String field) throws Exception {
        User owner = fx.owner(fx.org());

        ResultActions r = mvc.perform(post(path).with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));
        if (field != null) r.andExpect(jsonPath("$.error.fields." + field).exists());

        verifyNoInteractions(chatClient, ideogram);
    }

    /** The org check runs before metering, so a cross-org probe neither calls the provider nor spends quota. */
    @Test
    void regenerating_another_orgs_concept_is_a_404_without_a_paid_call_or_spent_quota() throws Exception {
        Organization other = fx.org();
        GeneratedEvent foreign = new GeneratedEvent();
        foreign.setOrgId(other.getId());
        foreign.setStatus(GeneratedEventStatus.COMPLETE);
        foreign.setCreatedAt(LocalDateTime.now());
        foreign.setVibe(VIBE);
        foreign = generated.save(foreign);
        User owner = fx.owner(fx.org());

        mvc.perform(post("/api/v1/ai/events/concept/regenerate").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("conceptId", foreign.getId().toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        verifyNoInteractions(chatClient, ideogram);
        assertThat(usage.countByUserIdAndKindAndCreatedAtAfter(owner.getId(), "image", Instant.EPOCH)).isZero();
    }

    /** The event's org check runs before metering, so another org's eventId is a 404 that spends no quota. */
    @Test
    void creating_against_another_orgs_event_is_a_404_without_a_paid_call_or_spent_quota() throws Exception {
        Organization other = fx.org();
        Event foreign = fx.event(other, fx.owner(other), EventStatus.DRAFT, null);
        User owner = fx.owner(fx.org());

        mvc.perform(post("/api/v1/ai/events/concept").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("vibe", VIBE, "eventId", foreign.getId().toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        verifyNoInteractions(chatClient, ideogram);
        assertThat(usage.countByUserIdAndKindAndCreatedAtAfter(owner.getId(), "image", Instant.EPOCH)).isZero();
    }

    /** An unknown vibeId is refused before metering on both paths; regenerate reads it from the stored snapshot. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void an_unknown_vibeId_is_FIELD_INVALID_without_a_paid_call_or_spent_quota(boolean regenerate) throws Exception {
        Organization org = fx.org();
        User owner = fx.owner(org);
        String path = "/api/v1/ai/events/concept";
        Map<String, Object> body = Map.of("vibe", VIBE, "vibeId", "no-such-vibe");
        if (regenerate) {
            GeneratedEvent prior = new GeneratedEvent();
            prior.setOrgId(org.getId());
            prior.setStatus(GeneratedEventStatus.COMPLETE);
            prior.setCreatedAt(LocalDateTime.now());
            prior.setVibe(VIBE);
            prior.setRequestVibeId("no-such-vibe");
            prior = generated.save(prior);
            path = "/api/v1/ai/events/concept/regenerate";
            body = Map.of("conceptId", prior.getId().toString());
        }

        mvc.perform(post(path).with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));

        verifyNoInteractions(chatClient, ideogram);
        assertThat(usage.countByUserIdAndKindAndCreatedAtAfter(owner.getId(), "image", Instant.EPOCH)).isZero();
    }

    /** The text-only concept set is burst-limited but never metered against the paid image quota. */
    @Test
    void a_concept_set_is_served_without_spending_image_quota() throws Exception {
        User owner = fx.owner(fx.org());
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.call()).thenReturn(call);
        when(call.entity(ConceptSet.class)).thenReturn(new ConceptSet(List.of(
                concept("Warehouse Mass"), concept("Concrete Hours"), concept("After Hours Mass"))));

        mvc.perform(post("/api/v1/ai/events/concepts").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("vibe", VIBE, "genre", "Techno", "city", "Berlin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concepts.length()").value(3))
                .andExpect(jsonPath("$.concepts[0].name").value("Warehouse Mass"))
                .andExpect(jsonPath("$.concepts[0].conceptId").isNotEmpty());

        assertThat(usage.countByUserIdAndKindAndCreatedAtAfter(owner.getId(), "image", Instant.EPOCH)).isZero();
        verifyNoInteractions(ideogram);
    }

    /** Quota counts attempts: metered before the render, so an upstream failure is a 502 that still counts. */
    @Test
    void an_upstream_failure_is_a_502_that_still_counts_the_attempt() throws Exception {
        User owner = fx.owner(fx.org());
        when(chatClient.prompt()).thenThrow(new IllegalStateException("OpenRouter unavailable"));

        mvc.perform(post("/api/v1/ai/events/concept").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("vibe", VIBE, "genre", "Techno", "city", "Berlin"))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE"));

        assertThat(usage.countByUserIdAndKindAndCreatedAtAfter(owner.getId(), "image", Instant.EPOCH)).isEqualTo(1);
    }

    private static ConceptSet.LlmConcept concept(String name) {
        return new ConceptSet.LlmConcept(name, "Deep in a raw warehouse",
                new ConceptSet.LlmCaptions("ig", "tt", "x"), "Techno", "Rave", 200);
    }
}

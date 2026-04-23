package com.imin.iminapi.controller.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.ai.ConceptRegenerateRequest;
import com.imin.iminapi.dto.ai.ConceptRequest;
import com.imin.iminapi.dto.ai.ConceptResponse;
import com.imin.iminapi.dto.ai.PosterDto;
import com.imin.iminapi.dto.ai.SuggestedTierDto;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.ai.ConceptStudioService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class ConceptControllerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();
    @MockitoBean ConceptStudioService studio;
    @MockitoBean com.imin.iminapi.web.IdempotencyKeySupport idempotency;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = StubFactory.class)
    public @interface WithStubUser {}

    public static class StubFactory implements WithSecurityContextFactory<WithStubUser> {
        @Override public org.springframework.security.core.context.SecurityContext createSecurityContext(WithStubUser ann) {
            AuthPrincipal p = new AuthPrincipal(USER, ORG, UserRole.OWNER, UUID.randomUUID());
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    private ConceptResponse sample() {
        return new ConceptResponse(UUID.randomUUID(), "ANTRUM", "Deep...",
                List.of(new PosterDto("https://cdn/p1.png", "V1", "linear-gradient(...)"),
                        new PosterDto("https://cdn/p2.png", "V2", "linear-gradient(...)"),
                        new PosterDto("https://cdn/p3.png", "V3", "linear-gradient(...)")),
                List.of("#1a1a18", "#2d5cff"),
                List.of(new SuggestedTierDto("Early Bird", 1200, 50),
                        new SuggestedTierDto("Standard", 1800, 150),
                        new SuggestedTierDto("Door", 2400, 50)),
                250, 78);
    }

    @Test
    @WithStubUser
    void post_concept_returns_full_payload() throws Exception {
        ConceptResponse s = sample();
        when(studio.create(any(), any(ConceptRequest.class))).thenReturn(s);
        // Mock idempotency to avoid DB FK constraint in tests
        when(idempotency.toCached(any(Integer.class), any())).thenAnswer(inv ->
                new com.imin.iminapi.web.IdempotencyKeySupport.Cached(200, om.writeValueAsString(inv.getArgument(1))));
        when(idempotency.runOrReplay(any(), any(), any(), any())).thenAnswer(inv -> {
            java.util.function.Supplier<?> supplier = inv.getArgument(3);
            return supplier.get();
        });
        mvc.perform(post("/api/v1/ai/events/concept")
                        .header("Idempotency-Key", "abc-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "vibe", "Moody Berlin techno warehouse vibe",
                                "genre", "Techno", "city", "Berlin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conceptId").exists())
                .andExpect(jsonPath("$.name").value("ANTRUM"))
                .andExpect(jsonPath("$.posters.length()").value(3))
                .andExpect(jsonPath("$.suggestedTiers[0].priceMinor").value(1200));
    }

    @Test
    @WithStubUser
    void post_concept_with_short_vibe_returns_FIELD_INVALID() throws Exception {
        mvc.perform(post("/api/v1/ai/events/concept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("vibe", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));
    }

    @Test
    @WithStubUser
    void post_concept_regenerate_passes_through() throws Exception {
        UUID cid = UUID.randomUUID();
        when(studio.regenerate(any(), eq(cid), any())).thenReturn(sample());
        mvc.perform(post("/api/v1/ai/events/concept/regenerate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("conceptId", cid.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conceptId").exists());
    }
}

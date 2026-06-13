package com.imin.iminapi.controller.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.ai.CaptionsDto;
import com.imin.iminapi.dto.ai.ConceptCardDto;
import com.imin.iminapi.dto.ai.ConceptSetRequest;
import com.imin.iminapi.dto.ai.ConceptSetResponse;
import com.imin.iminapi.dto.ai.SuggestedTierDto;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.ai.ConceptSetService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class ConceptControllerConceptsTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();
    @MockitoBean ConceptSetService conceptSet;

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

    private ConceptCardDto card(String name) {
        return new ConceptCardDto(UUID.randomUUID(), name, "Deep in a raw warehouse...",
                new CaptionsDto("ig copy #techno", "tiktok copy", "x copy"),
                "Techno", "Rave", 200,
                List.of(new SuggestedTierDto("Early Bird", 1200, 40),
                        new SuggestedTierDto("Standard", 1800, 120),
                        new SuggestedTierDto("Door", 2400, 40)));
    }

    private ConceptSetResponse sample() {
        return new ConceptSetResponse(UUID.randomUUID(),
                List.of(card("Warehouse Mass"), card("Concrete Hours"), card("After Hours Mass")));
    }

    @Test
    @WithStubUser
    void post_concepts_returns_three_concepts() throws Exception {
        when(conceptSet.create(any(), any(ConceptSetRequest.class))).thenReturn(sample());
        mvc.perform(post("/api/v1/ai/events/concepts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "vibe", "Moody Berlin techno warehouse vibe",
                                "genre", "Techno", "city", "Berlin", "capacity", 200))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedEventId").exists())
                .andExpect(jsonPath("$.concepts.length()").value(3))
                .andExpect(jsonPath("$.concepts[0].conceptId").exists())
                .andExpect(jsonPath("$.concepts[0].name").value("Warehouse Mass"))
                .andExpect(jsonPath("$.concepts[0].captions.instagram").value("ig copy #techno"))
                .andExpect(jsonPath("$.concepts[0].captions.tiktok").value("tiktok copy"))
                .andExpect(jsonPath("$.concepts[0].captions.x").value("x copy"))
                .andExpect(jsonPath("$.concepts[0].suggestedTiers[0].priceMinor").value(1200))
                .andExpect(jsonPath("$.concepts[0].suggestedTiers[0].quantity").value(40));
    }

    @Test
    @WithStubUser
    void post_concepts_with_short_vibe_returns_FIELD_INVALID() throws Exception {
        mvc.perform(post("/api/v1/ai/events/concepts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("vibe", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));
    }
}

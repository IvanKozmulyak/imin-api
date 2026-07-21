package com.imin.iminapi.audience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.dto.AiSegmentDraftResponse;
import com.imin.iminapi.audience.service.AiSegmentService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer (MockMvc) tests for {@code POST /api/v1/audience/segments/ai-draft}.
 * The service is mocked — this asserts the HTTP contract: auth, input validation, JSON shape,
 * and that the endpoint does not collide with the sibling segment routes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class AiSegmentControllerWebTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();

    @MockitoBean AiSegmentService aiSegmentService;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = StubFactory.class)
    public @interface WithStubUser {}

    public static class StubFactory implements WithSecurityContextFactory<WithStubUser> {
        @Override
        public org.springframework.security.core.context.SecurityContext createSecurityContext(WithStubUser ann) {
            AuthPrincipal p = new AuthPrincipal(USER, ORG, UserRole.OWNER, UUID.randomUUID());
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    private AiSegmentDraftResponse sample() {
        return new AiSegmentDraftResponse(
                "Big Spenders",
                "[{\"field\":\"spend_minor\",\"operator\":\">=\",\"value\":\"10000\"}]",
                List.of(Map.of("field", "spend_minor", "operator", ">=", "value", "10000")),
                "Spent at least €100 in total.",
                List.of("Spent at least €100 in total."),
                42, 30, List.of(), true);
    }

    @Test
    @WithStubUser
    void ai_draft_happy_path_returns_draft() throws Exception {
        when(aiSegmentService.draft(any(), eq("everyone who spent over €100"))).thenReturn(sample());

        mvc.perform(post("/api/v1/audience/segments/ai-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("prompt", "everyone who spent over €100"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Big Spenders"))
                .andExpect(jsonPath("$.rulesJson").value("[{\"field\":\"spend_minor\",\"operator\":\">=\",\"value\":\"10000\"}]"))
                .andExpect(jsonPath("$.matchedCount").value(42))
                .andExpect(jsonPath("$.mailableCount").value(30))
                .andExpect(jsonPath("$.createAllowed").value(true))
                .andExpect(jsonPath("$.explanationLines[0]").value("Spent at least €100 in total."));
    }

    @Test
    @WithStubUser
    void ai_draft_degraded_response_is_200_not_error() throws Exception {
        AiSegmentDraftResponse degraded = new AiSegmentDraftResponse(
                "AI segment", "[]", List.of(), "", List.of(),
                0, 0, List.of("house music", "Berlin"), false);
        when(aiSegmentService.draft(any(), any())).thenReturn(degraded);

        mvc.perform(post("/api/v1/audience/segments/ai-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("prompt", "fans of house music in Berlin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createAllowed").value(false))
                .andExpect(jsonPath("$.rulesJson").value("[]"))
                .andExpect(jsonPath("$.unsupported.length()").value(2));
    }

    @Test
    @WithStubUser
    void ai_draft_blank_prompt_is_field_invalid() throws Exception {
        mvc.perform(post("/api/v1/audience/segments/ai-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("prompt", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));
    }

    @Test
    @WithStubUser
    void ai_draft_overlong_prompt_is_field_invalid() throws Exception {
        String tooLong = "a".repeat(501);
        mvc.perform(post("/api/v1/audience/segments/ai-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("prompt", tooLong))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"));
    }

    @Test
    void ai_draft_unauthenticated_is_blocked() throws Exception {
        mvc.perform(post("/api/v1/audience/segments/ai-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("prompt", "everyone"))))
                .andExpect(status().is4xxClientError());
    }
}

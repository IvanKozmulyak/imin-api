package com.imin.iminapi.controller.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.AiGenerationUsage;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AiGenerationUsageRepository;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The daily quota returns the standard {@code {error:{code,message,fields}}} envelope with a 429
 * once the rolling-24h image count is at the limit (default 3). Burst RateLimiter is stubbed
 * generous by {@link TestRateLimitConfig}, so this exercises the quota layer specifically.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class AiQuotaControllerTest {

    @Autowired MockMvc mvc;
    @Autowired AiGenerationUsageRepository usageRepo;
    final ObjectMapper om = new ObjectMapper();
    @MockitoBean ConceptStudioService studio;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

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

    private void seedImageUsage(int n) {
        for (int i = 0; i < n; i++) {
            AiGenerationUsage u = new AiGenerationUsage();
            u.setUserId(USER);
            u.setOrgId(ORG);
            u.setKind("image");
            u.setCreatedAt(Instant.now());
            usageRepo.save(u);
        }
    }

    @Test
    @WithStubUser
    void concept_over_daily_quota_returns_429_envelope() throws Exception {
        seedImageUsage(3); // == default limit

        mvc.perform(post("/api/v1/ai/events/concept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "vibe", "Moody Berlin techno warehouse vibe",
                                "genre", "Techno", "city", "Berlin"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("AI_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.error.fields.limit").value("3"))
                .andExpect(jsonPath("$.error.fields.used").value("3"))
                .andExpect(jsonPath("$.error.fields.resetAt").exists());
    }
}

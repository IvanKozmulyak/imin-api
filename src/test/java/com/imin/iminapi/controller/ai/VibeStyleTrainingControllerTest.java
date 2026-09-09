package com.imin.iminapi.controller.ai;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.ImageProvider;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.poster.VibeStyleTrainingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class VibeStyleTrainingControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean VibeStyleTrainingService trainingService;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = StubFactory.class)
    public @interface WithStubUser {}

    public static class StubFactory implements WithSecurityContextFactory<WithStubUser> {
        @Override
        public org.springframework.security.core.context.SecurityContext createSecurityContext(WithStubUser ann) {
            AuthPrincipal p = new AuthPrincipal(USER, ORG, com.imin.iminapi.model.UserRole.OWNER, UUID.randomUUID());
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = MemberFactory.class)
    public @interface WithStubMember {}

    public static class MemberFactory implements WithSecurityContextFactory<WithStubMember> {
        @Override
        public org.springframework.security.core.context.SecurityContext createSecurityContext(WithStubMember ann) {
            AuthPrincipal p = new AuthPrincipal(USER, ORG, com.imin.iminapi.model.UserRole.MEMBER, UUID.randomUUID());
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    /**
     * api-11: this controller documents itself as an "Admin tool", spends live Recraft credits
     * and upserts the platform-wide vibe_style row every org's posters resolve against — while
     * being reachable by any authenticated organizer, because SecurityConfig gates the organizer
     * surface on .authenticated() alone. The principal was logged and then dropped: no role, no
     * org dimension. Same RoleGuard seniority axis api-1/api-7 put on the other privileged
     * endpoints.
     */
    @Test
    @WithStubMember
    void trainStyle_is_forbidden_for_a_MEMBER() throws Exception {
        mvc.perform(post("/api/v1/ai/vibes/brutalist_techno/train-style"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        org.mockito.Mockito.verifyNoInteractions(trainingService);
    }

    @Test
    @WithStubUser
    void trainStyle_returnsPersistedStyleId() throws Exception {
        when(trainingService.trainRecraftStyle(eq("brutalist_techno")))
                .thenReturn(new VibeStyleTrainingService.TrainResult(
                        "brutalist_techno", ImageProvider.RECRAFT, "style-trained-001",
                        LocalDateTime.of(2026, 6, 3, 10, 0)));

        mvc.perform(post("/api/v1/ai/vibes/brutalist_techno/train-style"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vibeId").value("brutalist_techno"))
                .andExpect(jsonPath("$.provider").value("RECRAFT"))
                .andExpect(jsonPath("$.styleId").value("style-trained-001"));
    }

    @Test
    @WithStubUser
    void trainStyle_unknownVibe_returns404() throws Exception {
        when(trainingService.trainRecraftStyle(eq("nope")))
                .thenThrow(ApiException.notFound("Vibe 'nope'"));

        mvc.perform(post("/api/v1/ai/vibes/nope/train-style"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void trainStyle_requiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/ai/vibes/brutalist_techno/train-style"))
                .andExpect(status().is4xxClientError());
    }
}

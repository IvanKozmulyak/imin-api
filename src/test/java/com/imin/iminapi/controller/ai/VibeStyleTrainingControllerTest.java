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

package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 12 — cancel/retry endpoints on the marketing controller. The controller is
 * {@code CampaignController} at {@code /api/v1/marketing/campaigns} (Phase 2). Auth is the
 * {@code /api/v1/**}->authenticated() catch-all in {@code SecurityConfig}, so an
 * unauthenticated POST 401s before the controller. Uses the same self-declared
 * {@code @WithStubOrganizer} security-context stub as {@code CampaignControllerTest} (there is
 * no {@code com.imin.iminapi.security.WithStubOrganizer}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class MarketingCampaignActionsTest {

    @Autowired MockMvc mvc;
    @MockitoBean CampaignService campaignService;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = StubFactory.class)
    public @interface WithStubOrganizer {}

    public static class StubFactory implements WithSecurityContextFactory<WithStubOrganizer> {
        @Override public org.springframework.security.core.context.SecurityContext createSecurityContext(WithStubOrganizer ann) {
            AuthPrincipal p = new AuthPrincipal(USER, ORG, UserRole.OWNER, UUID.randomUUID());
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    @Test
    @WithStubOrganizer
    void cancelDelegatesToService() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/v1/marketing/campaigns/" + id + "/cancel"))
           .andExpect(status().isOk());
        Mockito.verify(campaignService).cancel(any(), eq(id));
    }

    @Test
    void cancelRequiresAuth() throws Exception {
        // No @WithStubOrganizer: SecurityConfig gates /api/v1/marketing/** via the
        // /api/v1/** -> authenticated() catch-all, so an unauthenticated POST returns 401.
        mvc.perform(post("/api/v1/marketing/campaigns/" + UUID.randomUUID() + "/cancel"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    @WithStubOrganizer
    void retryDelegatesToService() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.when(campaignService.retry(any(), eq(id))).thenReturn(null);
        mvc.perform(post("/api/v1/marketing/campaigns/" + id + "/retry"))
           .andExpect(status().isAccepted());
        Mockito.verify(campaignService).retry(any(), eq(id));
    }
}

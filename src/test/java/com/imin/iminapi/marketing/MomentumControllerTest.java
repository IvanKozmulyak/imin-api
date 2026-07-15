package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.dto.CampaignDto;      // Phase 2
import com.imin.iminapi.marketing.dto.MomentumSuggestionDto;
import com.imin.iminapi.marketing.model.Campaign;        // Phase 2
import com.imin.iminapi.marketing.service.MomentumService;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for the Momentum Engine endpoints (spec §6.4): list, approve
 * (returns the created origin='momentum' campaign), dismiss (204), and the auth gate.
 * Follows the marketing/refund controller-test convention — nested @WithStubOrganizer
 * (there is no shared com.imin.iminapi.security.WithStubOrganizer type).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class MomentumControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean MomentumService service;

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
    void listsSuggestions() throws Exception {
        when(service.list(any(), any())).thenReturn(List.of(new MomentumSuggestionDto(
                UUID.randomUUID(), UUID.randomUUID(), "launch_push", "suggested",
                "{}", "{\"subject\":\"Hi\"}", null, Instant.now())));
        mvc.perform(get("/api/v1/marketing/suggestions?status=suggested"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].triggerType").value("launch_push"));
    }

    @Test
    @WithStubOrganizer
    void approveReturnsMomentumCampaign() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.approve(any(), any())).thenReturn(sampleCampaign());
        mvc.perform(post("/api/v1/marketing/suggestions/" + id + "/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("momentum"))
                .andExpect(jsonPath("$.status").value("draft"));
    }

    @Test
    @WithStubOrganizer
    void dismissReturns204() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/v1/marketing/suggestions/" + id + "/dismiss"))
                .andExpect(status().isNoContent());
    }

    @Test
    void listRequiresAuth() throws Exception {
        mvc.perform(get("/api/v1/marketing/suggestions")).andExpect(status().isUnauthorized());
    }

    // Build a real CampaignDto from a minimal Phase-2 Campaign entity via the same
    // CampaignDto.from(...) factory MomentumService.approve uses (Task 7). Only the
    // three asserted fields (origin/status/channel) must be set for the JSON checks.
    private CampaignDto sampleCampaign() {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(UUID.randomUUID());
        c.setChannel("email");
        c.setName("Momentum campaign");
        c.setStatus("draft");
        c.setOrigin("momentum");
        c.setCreatedBy(UUID.randomUUID());
        return CampaignDto.from(c);
    }
}

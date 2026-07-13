package com.imin.iminapi.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.controller.CampaignController;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class CampaignSendControllerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();
    @MockitoBean CampaignService campaignService;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000011");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000012");
    static final UUID CAMPAIGN = UUID.fromString("00000000-0000-0000-0000-000000000013");

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
    void send_missingIdempotencyKey_returns400() throws Exception {
        doThrow(new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_IDEMPOTENCY_KEY,
                "Idempotency-Key header is required"))
                .when(campaignService).send(eq(CAMPAIGN), any(), eq(null), any());

        mvc.perform(post("/api/v1/marketing/campaigns/{id}/send", CAMPAIGN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    @WithStubOrganizer
    void send_notInDraft_returns409() throws Exception {
        doThrow(new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                "Campaign is not in draft"))
                .when(campaignService).send(eq(CAMPAIGN), any(), eq("key-1"), any());

        mvc.perform(post("/api/v1/marketing/campaigns/{id}/send", CAMPAIGN)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    @WithStubOrganizer
    void send_valid_returns202() throws Exception {
        mvc.perform(post("/api/v1/marketing/campaigns/{id}/send", CAMPAIGN)
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());
    }
}

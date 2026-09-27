package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audienceplan.dto.DoorOptInPageResponse;
import com.imin.iminapi.audienceplan.dto.DoorOptInRequest;
import com.imin.iminapi.audienceplan.dto.DoorOptInResponse;
import com.imin.iminapi.audienceplan.dto.DoorOptInSettingsResponse;
import com.imin.iminapi.audienceplan.service.DoorOptInService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.RateLimiter;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Routing, security and rate limiting of the door QR endpoints; the service behind them is mocked. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class DoorOptInControllerWebTest {

    static final UUID ORG = UUID.fromString("dddddddd-0000-0000-0000-0000000d0001");
    static final UUID USER = UUID.fromString("dddddddd-0000-0000-0000-0000000d0010");
    static final UUID EVENT = UUID.fromString("dddddddd-0000-0000-0000-0000000d0100");

    @Autowired MockMvc mvc;
    @MockitoBean DoorOptInService service;
    @MockitoBean RateLimiter rateLimiter;

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = OrgFactory.class)
    @interface WithOrg {}

    static class OrgFactory implements WithSecurityContextFactory<WithOrg> {
        @Override
        public org.springframework.security.core.context.SecurityContext createSecurityContext(WithOrg ann) {
            AuthPrincipal p = new AuthPrincipal(USER, ORG, UserRole.MEMBER, UUID.randomUUID());
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    private static final String BODY = """
            {"token":"tok","email":"guest@example.com","consentGiven":true,
             "consentText":"Email me about events by Vechirka.","consentTextVersion":"door-org-named-2026-09","locale":"fr"}""";

    @Test
    void publicPost_isOpenWithoutAuth_chargesTheDoorBucketPerIp_andPassesTheBody() throws Exception {
        when(service.optIn(eq(EVENT), any())).thenReturn(DoorOptInResponse.ok());

        mvc.perform(post("/api/v1/public/events/{id}/door-optin", EVENT)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        verify(rateLimiter).consume("door-optin", "ip:127.0.0.1");
        verify(service).optIn(eq(EVENT), argThat((DoorOptInRequest r) -> r.token().equals("tok")
                && r.email().equals("guest@example.com") && Boolean.TRUE.equals(r.consentGiven())
                && r.consentText().equals("Email me about events by Vechirka.")
                && r.consentTextVersion().equals("door-org-named-2026-09") && r.locale().equals("fr")));
    }

    @Test
    void publicPost_rateLimited_is429AndNeverReachesTheService() throws Exception {
        doThrow(ApiException.rateLimited()).when(rateLimiter).consume("door-optin", "ip:127.0.0.1");

        mvc.perform(post("/api/v1/public/events/{id}/door-optin", EVENT)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        verify(service, never()).optIn(any(), any());
    }

    @Test
    void publicPost_notFound_usesTheStandardEnvelope() throws Exception {
        when(service.optIn(eq(EVENT), any())).thenThrow(ApiException.notFound("Event"));

        mvc.perform(post("/api/v1/public/events/{id}/door-optin", EVENT)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void publicGet_isOpenWithoutAuth_passesTheToken_andIsNotCached() throws Exception {
        when(service.page(EVENT, "tok")).thenReturn(new DoorOptInPageResponse(EVENT, "Door Night", "Vechirka",
                Instant.parse("2026-10-24T20:00:00Z"), "Europe/Paris", "Metz"));

        mvc.perform(get("/api/v1/public/events/{id}/door-optin", EVENT).param("t", "tok"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.organizerName").value("Vechirka"))
                .andExpect(jsonPath("$.eventName").value("Door Night"));
    }

    @Test
    void organizerGet_withoutAuth_is401() throws Exception {
        mvc.perform(get("/api/v1/events/{id}/door-optin", EVENT)).andExpect(status().isUnauthorized());
        verify(service, never()).settings(any(), any());
    }

    @Test
    @WithOrg
    void organizerGet_returnsSettingsForTheCallersOrg() throws Exception {
        when(service.settings(argThat(p -> p.orgId().equals(ORG)), eq(EVENT)))
                .thenReturn(new DoorOptInSettingsResponse(true, "https://app.imin.wtf/e/" + EVENT + "/door?t=tok", 3));

        mvc.perform(get("/api/v1/events/{id}/door-optin", EVENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.doorUrl").value("https://app.imin.wtf/e/" + EVENT + "/door?t=tok"))
                .andExpect(jsonPath("$.signups").value(3));
    }

    @Test
    @WithOrg
    void organizerPut_passesTheSwitch() throws Exception {
        when(service.setEnabled(any(), eq(EVENT), eq(false)))
                .thenReturn(new DoorOptInSettingsResponse(false, null, 0));

        mvc.perform(put("/api/v1/events/{id}/door-optin", EVENT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        verify(service).setEnabled(argThat(p -> p.orgId().equals(ORG)), eq(EVENT), eq(false));
    }

    @Test
    @WithOrg
    void organizerPut_withoutEnabled_is400() throws Exception {
        mvc.perform(put("/api/v1/events/{id}/door-optin", EVENT)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).setEnabled(any(), any(), eq(true));
        verify(service, never()).setEnabled(any(), any(), eq(false));
    }
}

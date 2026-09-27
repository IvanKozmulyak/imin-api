package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audienceplan.dto.SurveyPageResponse;
import com.imin.iminapi.audienceplan.dto.SurveyResponseRequest;
import com.imin.iminapi.audienceplan.dto.SurveyResponseResult;
import com.imin.iminapi.audienceplan.dto.SurveySettingsResponse;
import com.imin.iminapi.audienceplan.service.SurveyService;
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
import java.util.Map;
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

/** Routing, security, JSON binding and rate limiting of the survey endpoints; the service behind them is mocked. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class SurveyControllerWebTest {

    static final UUID ORG = UUID.fromString("eeeeeeee-0000-0000-0000-0000000e0001");
    static final UUID USER = UUID.fromString("eeeeeeee-0000-0000-0000-0000000e0010");
    static final UUID EVENT = UUID.fromString("eeeeeeee-0000-0000-0000-0000000e0100");

    @Autowired MockMvc mvc;
    @MockitoBean SurveyService service;
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
            {"homeCommune":"Metz","otherGenres":["pop","house & techno"],"heardFrom":"instagram","ageBand":"25_34",
             "firstTime":true,"noticeVersion":"survey-notice-2026-10","locale":"fr","consentGiven":true,
             "email":"guest@example.com","consentText":"Email me about events by Vechirka.",
             "consentTextVersion":"survey-org-named-2026-09"}""";

    @Test
    void publicPost_isOpenWithoutAuth_chargesTheSurveyBucketPerIp_andBindsEveryField() throws Exception {
        when(service.submit(eq("tok"), any())).thenReturn(SurveyResponseResult.ok());

        mvc.perform(post("/api/v1/public/surveys/{token}", "tok")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        verify(rateLimiter).consume("survey", "ip:127.0.0.1");
        verify(service).submit(eq("tok"), argThat((SurveyResponseRequest r) -> r.homeCommune().equals("Metz")
                && r.otherGenres().equals(List.of("pop", "house & techno")) && r.heardFrom().equals("instagram")
                && r.ageBand().equals("25_34") && Boolean.TRUE.equals(r.firstTime())
                && r.noticeVersion().equals("survey-notice-2026-10") && r.locale().equals("fr")
                && Boolean.TRUE.equals(r.consentGiven()) && r.email().equals("guest@example.com")
                && r.consentText().equals("Email me about events by Vechirka.")
                && r.consentTextVersion().equals("survey-org-named-2026-09")
                && (r.unknownFields() == null || r.unknownFields().isEmpty())));
    }

    @Test
    void publicPost_unknownProperty_reachesTheServiceAsAnUnknownField() throws Exception {
        when(service.submit(eq("tok"), any())).thenReturn(SurveyResponseResult.ok());

        mvc.perform(post("/api/v1/public/surveys/{token}", "tok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"heardFrom\":\"friend\",\"nationality\":\"FR\"}"))
                .andExpect(status().isOk());

        verify(service).submit(eq("tok"), argThat((SurveyResponseRequest r) -> r.heardFrom().equals("friend")
                && Map.of("nationality", "FR").equals(r.unknownFields())));
    }

    @Test
    void publicPost_rateLimited_is429AndNeverReachesTheService() throws Exception {
        doThrow(ApiException.rateLimited()).when(rateLimiter).consume("survey", "ip:127.0.0.1");

        mvc.perform(post("/api/v1/public/surveys/{token}", "tok")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        verify(service, never()).submit(any(), any());
    }

    @Test
    void publicPost_malformedJson_is400BeforeTheBucketAndTheTokenLookup() throws Exception {
        mvc.perform(post("/api/v1/public/surveys/{token}", "unknown")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"otherGenres\":\"pop\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Malformed request body"));

        verify(rateLimiter, never()).consume(any(), any());
        verify(service, never()).submit(any(), any());
    }

    @Test
    void publicPost_notFound_usesTheStandardEnvelope() throws Exception {
        when(service.submit(eq("tok"), any())).thenThrow(ApiException.notFound("Event"));

        mvc.perform(post("/api/v1/public/surveys/{token}", "tok")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void publicGet_isOpenWithoutAuth_passesTheToken_andIsNotCached() throws Exception {
        when(service.page("tok")).thenReturn(new SurveyPageResponse(EVENT, "Survey Night", "Vechirka",
                "Vechirka SAS", "hello@vechirka.test", Instant.parse("2026-09-19T20:00:00Z"), "Europe/Paris", "Metz"));

        mvc.perform(get("/api/v1/public/surveys/{token}", "tok"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.eventId").value(EVENT.toString()))
                .andExpect(jsonPath("$.organizerName").value("Vechirka"))
                .andExpect(jsonPath("$.organizerLegalName").value("Vechirka SAS"))
                .andExpect(jsonPath("$.organizerLegalContact").value("hello@vechirka.test"))
                .andExpect(jsonPath("$.eventName").value("Survey Night"));
    }

    @Test
    void openapi_publishesTheSurveyResponseRequestMarker_withoutTheUnknownFieldsBucket() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.SurveyResponseRequest.properties.otherGenres").exists())
                .andExpect(jsonPath("$.components.schemas.SurveyResponseRequest.properties.noticeVersion").exists())
                .andExpect(jsonPath("$.components.schemas.SurveyResponseRequest.properties.unknownFields").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/public/surveys/{token}'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/events/{eventId}/survey'].put").exists());
    }

    @Test
    void organizerGet_withoutAuth_is401() throws Exception {
        mvc.perform(get("/api/v1/events/{id}/survey", EVENT)).andExpect(status().isUnauthorized());
        verify(service, never()).settings(any(), any());
    }

    @Test
    @WithOrg
    void organizerGet_returnsSettingsForTheCallersOrg() throws Exception {
        when(service.settings(argThat(p -> p.orgId().equals(ORG)), eq(EVENT)))
                .thenReturn(new SurveySettingsResponse(true, "https://app.imin.wtf/e/" + EVENT + "/survey?t=tok", 4));

        mvc.perform(get("/api/v1/events/{id}/survey", EVENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.surveyUrl").value("https://app.imin.wtf/e/" + EVENT + "/survey?t=tok"))
                .andExpect(jsonPath("$.responses").value(4));
    }

    @Test
    @WithOrg
    void organizerPut_passesTheSwitch() throws Exception {
        when(service.setEnabled(any(), eq(EVENT), eq(true)))
                .thenReturn(new SurveySettingsResponse(true, "https://app.imin.wtf/e/" + EVENT + "/survey?t=tok", 0));

        mvc.perform(put("/api/v1/events/{id}/survey", EVENT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        verify(service).setEnabled(argThat(p -> p.orgId().equals(ORG)), eq(EVENT), eq(true));
    }

    @Test
    @WithOrg
    void organizerPut_withoutEnabled_is400() throws Exception {
        mvc.perform(put("/api/v1/events/{id}/survey", EVENT)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).setEnabled(any(), any(), eq(true));
        verify(service, never()).setEnabled(any(), any(), eq(false));
    }
}

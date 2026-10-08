package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.audienceplan.dto.SurveyResponseRequest;
import com.imin.iminapi.audienceplan.service.SurveyService;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.RecordingRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Routing, security, JSON binding and rate limiting of the survey endpoints, over the real service. */
@IminIntegrationTest
class SurveyControllerWebTest {

    private static final String NOTICE = "survey-notice-2026-10";
    private static final String VERSION = "survey-org-named-2026-09";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IminFixtures fx;
    @Autowired SurveyService surveyService;
    @Autowired RecordingRateLimiter limiter;
    @Autowired EmailProperties emailProps;
    @Autowired Clock clock;
    @Autowired @Qualifier(FanFeatureExecutors.LIVE) Executor fanFeatureExecutor;

    private Organization org;
    private AuthPrincipal owner;
    private Event event;
    private String token;
    private String text;

    @BeforeEach
    void setUp() {
        org = fx.org();
        User user = fx.owner(org);
        owner = fx.principal(user);
        event = fx.event(org, user, EventStatus.PAST, clock.instant().minus(Duration.ofDays(1)));
        jdbc.update("update events set published_at = ? where id = ?",
                Timestamp.from(clock.instant().minus(Duration.ofDays(30))), event.getId());
        token = surveyService.setEnabled(owner, event.getId(), true).surveyUrl().replaceAll(".*\\?t=", "");
        text = "Email me about events by " + org.getName() + ".";
    }

    @AfterEach
    void tearDown() {
        // A stored consent recomputes the member's features on the live pool; let it finish before the rows go.
        AsyncDrain.drain(fanFeatureExecutor);
        UUID o = org.getId();
        List<UUID> consumers = jdbc.queryForList("select consumer_id from memberships where org_id = ?", UUID.class, o);
        jdbc.update("delete from survey_responses where org_id = ?", o);
        jdbc.update("delete from fan_features where org_id = ?", o);
        jdbc.update("delete from memberships where org_id = ?", o);
        for (UUID c : consumers) jdbc.update("delete from consumers where consumer_id = ?", c);
        jdbc.update("delete from events where org_id = ?", o);
        jdbc.update("delete from users where org_id = ?", o);
        jdbc.update("delete from organizations where id = ?", o);
    }

    @Test
    void publicPost_isOpenWithoutAuth_chargesTheSurveyBucketPerIp_andBindsEveryField() throws Exception {
        String email = fx.email("survey");
        String body = """
                {"homeCommune":"Metz","otherGenres":["pop","house & techno"],"heardFrom":"instagram","ageBand":"25_34",
                 "firstTime":true,"noticeVersion":"%s","locale":"fr","consentGiven":true,
                 "email":"%s","consentText":"%s","consentTextVersion":"%s"}""".formatted(NOTICE, email, text, VERSION);

        mvc.perform(post("/api/v1/public/surveys/{token}", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        assertThat(limiter.calls()).contains(new RecordingRateLimiter.Call("survey", "ip:127.0.0.1"));
        Map<String, Object> r = jdbc.queryForMap("select * from survey_responses where event_id = ?", event.getId());
        assertThat(r).containsEntry("home_commune", "Metz").containsEntry("other_genres", "[\"pop\",\"house & techno\"]")
                .containsEntry("heard_from", "instagram").containsEntry("age_band", "25_34")
                .containsEntry("first_time", true).containsEntry("notice_version", NOTICE).containsEntry("locale", "fr");
        Map<String, Object> c = jdbc.queryForMap("select cr.* from consent_records cr join memberships m"
                + " on m.membership_id = cr.membership_id join consumers k on k.consumer_id = m.consumer_id"
                + " where m.org_id = ? and k.normalized_email = ?", org.getId(), email);
        assertThat(c).containsEntry("text_version", VERSION)
                .containsEntry("event_id", event.getId());
        assertThat((String) c.get("proof_text")).contains("\"" + text + "\"").contains("(locale fr)");
    }

    @Test
    void publicPost_unknownProperty_is400NamingIt_andStoresNothing() throws Exception {
        mvc.perform(post("/api/v1/public/surveys/{token}", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"heardFrom\":\"friend\",\"nationality\":\"FR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.fields.nationality").exists())
                .andExpect(jsonPath("$.error.fields.heardFrom").doesNotExist());

        assertThat(responseCount()).isZero();
    }

    @Test
    void publicPost_rateLimited_is429AndStoresNothing() throws Exception {
        limiter.limit("survey", 0);

        mvc.perform(post("/api/v1/public/surveys/{token}", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"heardFrom\":\"friend\",\"noticeVersion\":\""
                                + NOTICE + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        assertThat(responseCount()).isZero();
    }

    @Test
    void publicPost_malformedJson_is400BeforeTheBucketAndTheTokenLookup() throws Exception {
        mvc.perform(post("/api/v1/public/surveys/{token}", token + "x")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"otherGenres\":\"pop\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message").value("Malformed request body"));

        assertThat(limiter.calls()).isEmpty();
    }

    @Test
    void publicPost_notFound_usesTheStandardEnvelope() throws Exception {
        mvc.perform(post("/api/v1/public/surveys/{token}", token + "x")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"heardFrom\":\"friend\",\"noticeVersion\":\""
                                + NOTICE + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void publicGet_isOpenWithoutAuth_passesTheToken_andIsNotCached() throws Exception {
        jdbc.update("update organizations set legal_name = ?, legal_contact = ? where id = ?",
                "Vechirka SAS", "hello@vechirka.test", org.getId());

        mvc.perform(get("/api/v1/public/surveys/{token}", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.eventId").value(event.getId().toString()))
                .andExpect(jsonPath("$.organizerName").value(org.getName()))
                .andExpect(jsonPath("$.organizerLegalName").value("Vechirka SAS"))
                .andExpect(jsonPath("$.organizerLegalContact").value("hello@vechirka.test"))
                .andExpect(jsonPath("$.eventName").value(event.getName()));
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
        mvc.perform(get("/api/v1/events/{id}/survey", event.getId())).andExpect(status().isUnauthorized());
    }

    @Test
    void organizerGetAndPut_roundTripTheSwitchForTheCallersOrg() throws Exception {
        surveyService.submit(token, new SurveyResponseRequest("Metz", null, null, null, null, NOTICE, "en", null, null,
                null, null, null));
        String url = emailProps.getBuyerSiteBaseUrl() + "/e/" + event.getId() + "/survey?t=" + token;

        mvc.perform(get("/api/v1/events/{id}/survey", event.getId()).with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.surveyUrl").value(url))
                .andExpect(jsonPath("$.responses").value(1));

        mvc.perform(put("/api/v1/events/{id}/survey", event.getId()).with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(get("/api/v1/events/{id}/survey", event.getId()).with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.surveyUrl").value(url));
    }

    @Test
    void organizerPut_withoutEnabled_is400_andLeavesTheSwitch() throws Exception {
        mvc.perform(put("/api/v1/events/{id}/survey", event.getId()).with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("select survey_enabled from events where id = ?", Boolean.class,
                event.getId())).isTrue();
    }

    private int responseCount() {
        return jdbc.queryForObject("select count(*) from survey_responses where event_id = ?", Integer.class,
                event.getId());
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}

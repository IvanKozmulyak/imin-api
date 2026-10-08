package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.audienceplan.dto.DoorOptInRequest;
import com.imin.iminapi.audienceplan.service.DoorOptInService;
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

/** Routing, security and rate limiting of the door QR endpoints, over the real service and an own live event. */
@IminIntegrationTest
class DoorOptInControllerWebTest {

    private static final String VERSION = "door-org-named-2026-09";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IminFixtures fx;
    @Autowired DoorOptInService doorOptInService;
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
        event = fx.event(org, user, EventStatus.LIVE, clock.instant().plus(Duration.ofDays(3)));
        jdbc.update("update events set published_at = ? where id = ?",
                Timestamp.from(clock.instant().minus(Duration.ofHours(1))), event.getId());
        token = doorOptInService.setEnabled(owner, event.getId(), true).doorUrl().replaceAll(".*\\?t=", "");
        text = "Email me about events by " + org.getName() + ".";
    }

    @AfterEach
    void tearDown() {
        // A stored sign-up recomputes the member's features on the live pool; let it finish before the rows go.
        AsyncDrain.drain(fanFeatureExecutor);
        UUID o = org.getId();
        List<UUID> consumers = jdbc.queryForList("select consumer_id from memberships where org_id = ?", UUID.class, o);
        jdbc.update("delete from fan_features where org_id = ?", o);
        jdbc.update("delete from memberships where org_id = ?", o);
        for (UUID c : consumers) jdbc.update("delete from consumers where consumer_id = ?", c);
        jdbc.update("delete from events where org_id = ?", o);
        jdbc.update("delete from users where org_id = ?", o);
        jdbc.update("delete from organizations where id = ?", o);
    }

    @Test
    void publicPost_isOpenWithoutAuth_chargesTheDoorBucketPerIp_andStoresTheBody() throws Exception {
        String email = fx.email("door");

        mvc.perform(post("/api/v1/public/events/{id}/door-optin", event.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body(token, email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        assertThat(limiter.calls()).contains(new RecordingRateLimiter.Call("door-optin", "ip:127.0.0.1"));
        List<Map<String, Object>> rows = consentRows(email);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("text_version", VERSION).containsEntry("event_id", event.getId());
        assertThat((String) rows.get(0).get("proof_text")).contains("\"" + text + "\"").contains("(locale fr)");
    }

    @Test
    void publicPost_rateLimited_is429AndStoresNothing() throws Exception {
        String email = fx.email("door-limited");
        limiter.limit("door-optin", 0);

        mvc.perform(post("/api/v1/public/events/{id}/door-optin", event.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body(token, email)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        assertThat(jdbc.queryForObject("select count(*) from consumers where normalized_email = ?", Integer.class,
                email)).isZero();
    }

    @Test
    void publicPost_notFound_usesTheStandardEnvelope() throws Exception {
        mvc.perform(post("/api/v1/public/events/{id}/door-optin", event.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body("wrong", fx.email("door"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void publicGet_isOpenWithoutAuth_passesTheToken_andIsNotCached() throws Exception {
        mvc.perform(get("/api/v1/public/events/{id}/door-optin", event.getId()).param("t", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.organizerName").value(org.getName()))
                .andExpect(jsonPath("$.eventName").value(event.getName()));
    }

    @Test
    void organizerGet_withoutAuth_is401() throws Exception {
        mvc.perform(get("/api/v1/events/{id}/door-optin", event.getId())).andExpect(status().isUnauthorized());
    }

    @Test
    void organizerGetAndPut_roundTripTheSwitchForTheCallersOrg() throws Exception {
        doorOptInService.optIn(event.getId(), new DoorOptInRequest(token, fx.email("door"), true, text, VERSION, "en"));
        String url = emailProps.getBuyerSiteBaseUrl() + "/e/" + event.getId() + "/door?t=" + token;

        mvc.perform(get("/api/v1/events/{id}/door-optin", event.getId()).with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.doorUrl").value(url))
                .andExpect(jsonPath("$.signups").value(1));

        mvc.perform(put("/api/v1/events/{id}/door-optin", event.getId()).with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(get("/api/v1/events/{id}/door-optin", event.getId()).with(auth(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.doorUrl").value(url));
    }

    @Test
    void organizerPut_withoutEnabled_is400_andLeavesTheSwitch() throws Exception {
        mvc.perform(put("/api/v1/events/{id}/door-optin", event.getId()).with(auth(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("select door_optin_enabled from events where id = ?", Boolean.class,
                event.getId())).isTrue();
    }

    private String body(String t, String email) {
        return """
                {"token":"%s","email":"%s","consentGiven":true,
                 "consentText":"%s","consentTextVersion":"%s","locale":"fr"}""".formatted(t, email, text, VERSION);
    }

    private List<Map<String, Object>> consentRows(String email) {
        return jdbc.queryForList("select cr.* from consent_records cr join memberships m on m.membership_id = cr.membership_id"
                + " join consumers c on c.consumer_id = m.consumer_id where m.org_id = ? and c.normalized_email = ?",
                org.getId(), email);
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}

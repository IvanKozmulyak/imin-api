package com.imin.iminapi.controller.gate;

import com.imin.iminapi.dto.gate.GateLoginRequest;
import com.imin.iminapi.dto.gate.GateLoginResponse;
import com.imin.iminapi.model.AuthSession;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.AuthSessionRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.TokenService;
import com.imin.iminapi.service.gate.GateAuthService;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code GET /api/v1/gate/events}.
 *
 * <p>The flow exercised end-to-end:
 * <ul>
 *   <li>provision a gate credential, log in with {@link GateAuthService#login}
 *       to mint a real bearer token, then call the endpoint with it</li>
 *   <li>confirm ordering, the 24h running-window filter, and the cross-org
 *       isolation that the {@code me.orgId()} check enforces</li>
 *   <li>negative tests cover the missing-auth path and the user-JWT path
 *       (gate-events is gate-only)</li>
 * </ul>
 */
@IminIntegrationTest
class GateEventsControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired GateAuthService gateAuth;
    @Autowired EventRepository events;
    @Autowired AuthSessionRepository authSessions;
    @Autowired TokenService tokenService;
    @Autowired MutableClock clock;

    private Organization org;
    private User owner;
    private String gateToken;

    @BeforeEach
    void seed() {
        org = fx.org();
        owner = fx.owner(org);
        gateAuth.rotate(fx.principal(owner), org.getId(), "gate-password-12345");
        GateLoginResponse login = gateAuth.login(
                new GateLoginRequest(org.getSlug(), "gate-password-12345"));
        gateToken = login.token();
    }

    private Event makeEvent(Organization o, String name, Instant startsAt) {
        Event e = fx.event(o, owner, EventStatus.LIVE, startsAt);
        e.setName(name);
        return events.save(e);
    }

    // ── Happy path ─────────────────────────────────────────────────────────

    @Test
    void returns_org_events_in_startsAt_ascending_order() throws Exception {
        Instant now = clock.instant();
        Event later = makeEvent(org, "Z Later", now.plus(Duration.ofDays(7)));
        Event sooner = makeEvent(org, "A Sooner", now.plus(Duration.ofDays(1)));
        Event running = makeEvent(org, "M Running", now.minus(Duration.ofHours(2)));

        mvc.perform(get("/api/v1/gate/events")
                        .header("Authorization", "Bearer " + gateToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.length()").value(3))
                // ascending — running (now-2h) first, then sooner (+1d), then later (+7d)
                .andExpect(jsonPath("$[0].id").value(running.getId().toString()))
                .andExpect(jsonPath("$[0].title").value("M Running"))
                .andExpect(jsonPath("$[0].startsAt").exists())
                .andExpect(jsonPath("$[1].id").value(sooner.getId().toString()))
                .andExpect(jsonPath("$[2].id").value(later.getId().toString()));
    }

    // ── Filtering ──────────────────────────────────────────────────────────

    @Test
    void excludes_events_older_than_24h() throws Exception {
        Instant now = clock.instant();
        Event old = makeEvent(org, "Old", now.minus(Duration.ofDays(2)));
        Event upcoming = makeEvent(org, "Upcoming", now.plus(Duration.ofDays(1)));

        mvc.perform(get("/api/v1/gate/events")
                        .header("Authorization", "Bearer " + gateToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(upcoming.getId().toString()))
                .andExpect(jsonPath("$[?(@.id == '" + old.getId() + "')]").isEmpty());
    }

    @Test
    void excludes_events_from_other_orgs() throws Exception {
        Organization otherOrg = fx.org();

        Event mine = makeEvent(org, "Mine", clock.instant().plus(Duration.ofDays(1)));
        Event theirs = makeEvent(otherOrg, "Theirs", clock.instant().plus(Duration.ofDays(2)));

        mvc.perform(get("/api/v1/gate/events")
                        .header("Authorization", "Bearer " + gateToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(mine.getId().toString()))
                .andExpect(jsonPath("$[?(@.id == '" + theirs.getId() + "')]").isEmpty());
    }

    @Test
    void returns_empty_array_when_org_has_no_scannable_events() throws Exception {
        // No events at all for this org.
        mvc.perform(get("/api/v1/gate/events")
                        .header("Authorization", "Bearer " + gateToken))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    // ── Auth ──────────────────────────────────────────────────────────────

    @Test
    void returns_401_without_a_token() throws Exception {
        mvc.perform(get("/api/v1/gate/events"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_MISSING"));
    }

    @Test
    void user_jwt_is_rejected_with_401_because_endpoint_is_gate_only() throws Exception {
        // Mint a real user-session bearer token for the same org's owner; gate-events
        // must NOT accept it (it isn't a gate principal).
        TokenService.IssuedToken issued = tokenService.issue();
        AuthSession s = new AuthSession();
        s.setUserId(owner.getId());
        s.setTokenHash(issued.tokenHash());
        s.setExpiresAt(clock.instant().plus(Duration.ofDays(1)));
        authSessions.save(s);

        mvc.perform(get("/api/v1/gate/events")
                        .header("Authorization", "Bearer " + issued.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_MISSING"));
    }
}

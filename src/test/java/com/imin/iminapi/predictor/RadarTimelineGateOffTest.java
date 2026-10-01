package com.imin.iminapi.predictor;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Date check and Radar on, but the org is on no beta list: the org's own event is the gate's 404. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
@TestPropertySource(properties = {"imin.predictor.date-check.enabled=true",
        "imin.predictor.date-check.all-orgs=false",
        "imin.predictor.date-check.beta-org-ids=",
        "imin.predictor.date-check.radar-enabled=true"})
class RadarTimelineGateOffTest {

    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired JdbcTemplate jdbc;

    private Organization org;
    private User owner;
    private Event event;

    @BeforeEach
    void seed() {
        clean();
        Organization o = new Organization();
        o.setName("Radar Gate Org");
        o.setSlug("rg-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("rg@example.test");
        o.setCountry("FR");
        org = orgs.save(o);
        User u = new User();
        u.setOrgId(org.getId());
        u.setEmail("owner-" + UUID.randomUUID() + "@example.test");
        u.setRole(UserRole.OWNER);
        owner = users.save(u);
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Gated Night");
        e.setSlug("rg-" + UUID.randomUUID());
        e.setCreatedBy(owner.getId());
        e.setStatus(EventStatus.LIVE);
        e.setStartsAt(Instant.parse("2026-10-15T20:00:00Z"));
        event = events.save(e);
    }

    @AfterEach
    void after() {
        clean();
    }

    private void clean() {
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private Authentication mine() {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID()), null,
                List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    @Test
    void getIs404WhenGateClosed() throws Exception {
        mvc.perform(get("/api/v1/events/" + event.getId() + "/prediction/radar").with(authentication(mine())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("Date check not found"));
    }

    @Test
    void putIs404WhenGateClosedAndWritesNothing() throws Exception {
        mvc.perform(put("/api/v1/events/" + event.getId() + "/prediction/radar/mute").with(authentication(mine()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"muted\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("Date check not found"));
        assertThat(jdbc.queryForObject("select radar_muted from events where id = ?", Boolean.class, event.getId()))
                .isFalse();
    }
}

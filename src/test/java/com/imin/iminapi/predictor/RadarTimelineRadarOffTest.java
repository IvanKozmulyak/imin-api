package com.imin.iminapi.predictor;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.repository.DateCheckRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Date check on for every org, Radar off: the timeline says so and still lists past runs. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
@TestPropertySource(properties = {"imin.predictor.date-check.enabled=true",
        "imin.predictor.date-check.all-orgs=true",
        "imin.predictor.date-check.radar-enabled=false"})
class RadarTimelineRadarOffTest {

    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired DateCheckRepository checks;
    @Autowired JdbcTemplate jdbc;

    private Organization org;
    private User owner;

    @BeforeEach
    void seed() {
        clean();
        Organization o = new Organization();
        o.setName("Radar Off Org");
        o.setSlug("ro-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("ro@example.test");
        o.setCountry("FR");
        org = orgs.save(o);
        User u = new User();
        u.setOrgId(org.getId());
        u.setEmail("owner-" + UUID.randomUUID() + "@example.test");
        u.setRole(UserRole.OWNER);
        owner = users.save(u);
    }

    @AfterEach
    void after() {
        clean();
    }

    private void clean() {
        events.deleteAll();
        checks.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private Authentication mine() {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID()), null,
                List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    @Test
    void radarOffIsReportedAndRunsStillListed() throws Exception {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Off Night");
        e.setSlug("ro-" + UUID.randomUUID());
        e.setCreatedBy(owner.getId());
        e.setStatus(EventStatus.LIVE);
        e.setStartsAt(Instant.parse("2026-10-15T20:00:00Z"));
        e = events.save(e);
        UUID run = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-01T03:50:00Z");
        jdbc.update("""
                insert into date_check (id, org_id, created_by, city, country, genre_family, status,
                    question_bank_version, assumptions_json, research, event_id, created_at, updated_at, origin,
                    radar_milestone, radar_night, radar_prev_verdict, radar_prev_risk, radar_verdict, radar_risk)
                values (?, ?, ?, 'Paris', 'FR', 'house & techno', 'done', 'test', '[]', false, ?, ?, ?, 'radar',
                    14, ?, 'good', 1, 'adjust', 4)""",
                run, org.getId(), owner.getId(), e.getId(), Timestamp.from(at), Timestamp.from(at),
                LocalDate.of(2026, 10, 15));

        mvc.perform(get("/api/v1/events/" + e.getId() + "/prediction/radar").with(authentication(mine())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.radarOn").value(false))
                .andExpect(jsonPath("$.muted").value(false))
                .andExpect(jsonPath("$.runs.length()").value(1))
                .andExpect(jsonPath("$.runs[0].dateCheckId").value(run.toString()));
    }
}

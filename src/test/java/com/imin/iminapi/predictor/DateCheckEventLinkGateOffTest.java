package com.imin.iminapi.predictor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.model.DateCheck;
import com.imin.iminapi.predictor.model.DateCheckDate;
import com.imin.iminapi.predictor.repository.DateCheckDateRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Gate closed: the link is invisible and cannot be written, while subGenre stays ungated. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
@TestPropertySource(properties = "imin.predictor.date-check.enabled=false")
class DateCheckEventLinkGateOffTest {

    private static final LocalDate NIGHT = LocalDate.of(2026, 12, 5);

    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired DateCheckRepository checks;
    @Autowired DateCheckDateRepository checkDates;

    private final ObjectMapper om = new ObjectMapper();

    private Organization org;
    private User owner;

    @BeforeEach
    void seed() {
        clean();
        Organization o = new Organization();
        o.setName("Gate Off Org");
        o.setSlug("go-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("go@example.test");
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
        checkDates.deleteAll();
        events.deleteAll();
        checks.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private Authentication mine() {
        AuthPrincipal p = new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID());
        return new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    private DateCheck seedCheck(UUID eventId) {
        DateCheck c = new DateCheck();
        c.setOrgId(org.getId());
        c.setCreatedBy(owner.getId());
        c.setCity("Paris");
        c.setCountry("FR");
        c.setGenreFamily("house & techno");
        c.setStatus("done");
        c.setQuestionBankVersion("test");
        c.setEventId(eventId);
        c = checks.save(c);
        DateCheckDate d = new DateCheckDate();
        d.setDateCheckId(c.getId());
        d.setCandidateDate(NIGHT);
        d.setVerdict("good");
        d.setCoverage(new BigDecimal("0.800"));
        d.setRankOrder((short) 1);
        checkDates.save(d);
        return c;
    }

    private Event seedEvent(UUID dateCheckId) {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setCreatedBy(owner.getId());
        e.setName("Gate Off Night");
        e.setSlug("ev-" + UUID.randomUUID().toString().substring(0, 8));
        e.setStartsAt(Instant.parse("2026-12-05T20:00:00Z"));
        e.setDateCheckId(dateCheckId);
        return events.save(e);
    }

    @Test
    void gateOffOmitsDateCheck() throws Exception {
        DateCheck c = seedCheck(null);
        Event e = seedEvent(c.getId());
        c.setEventId(e.getId());
        checks.save(c);

        mvc.perform(get("/api/v1/events/" + e.getId() + "/prediction").with(authentication(mine())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("none"))
                .andExpect(jsonPath("$.dateCheck").doesNotExist());
    }

    @Test
    void gateOffCreateWithDateCheckIdIs404() throws Exception {
        DateCheck c = seedCheck(null);

        mvc.perform(post("/api/v1/events").with(authentication(mine())).contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "X", "dateCheckId", c.getId().toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Date check not found"));

        assertThat(events.count()).isZero();
        assertThat(checks.findById(c.getId()).orElseThrow().getEventId()).isNull();
    }

    @Test
    void gateOffSubGenreStillAccepted() throws Exception {
        Event e = seedEvent(null);

        mvc.perform(patch("/api/v1/events/" + e.getId()).with(authentication(mine()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subGenre\":\"techno\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subGenre").value("techno"));

        assertThat(events.findById(e.getId()).orElseThrow().getSubGenre()).isEqualTo("techno");
    }
}

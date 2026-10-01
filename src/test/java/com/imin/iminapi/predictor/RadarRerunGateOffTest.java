package com.imin.iminapi.predictor;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.predictor.model.DateCheck;
import com.imin.iminapi.predictor.model.DateCheckDate;
import com.imin.iminapi.predictor.repository.DateCheckDateRepository;
import com.imin.iminapi.predictor.repository.DateCheckRepository;
import com.imin.iminapi.predictor.service.DateCheckService;
import com.imin.iminapi.predictor.service.DateCheckService.RadarOutcome;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Date check and radar on, but the org is on no beta list: a due event is skipped and nothing is written. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
@TestPropertySource(properties = {"imin.predictor.date-check.enabled=true",
        "imin.predictor.date-check.all-orgs=false",
        "imin.predictor.date-check.beta-org-ids=",
        "imin.predictor.date-check.radar-enabled=true"})
class RadarRerunGateOffTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final LocalDate NIGHT = LocalDate.of(2026, 10, 15);

    @TestBean Clock clock;

    static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Autowired DateCheckService service;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired DateCheckRepository checks;
    @Autowired DateCheckDateRepository checkDates;

    private Organization org;
    private User owner;

    @BeforeEach
    void seed() {
        clean();
        Organization o = new Organization();
        o.setName("Radar Closed Org");
        o.setSlug("rc-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("rc@example.test");
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

    @Test
    void closedGateIsSkipped() {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Closed Night");
        e.setSlug("rc-" + UUID.randomUUID());
        e.setCreatedBy(owner.getId());
        e.setStatus(EventStatus.LIVE);
        e.setStartsAt(Instant.parse("2026-10-15T20:00:00Z"));
        e = events.save(e);
        DateCheck c = new DateCheck();
        c.setOrgId(org.getId());
        c.setCreatedBy(owner.getId());
        c.setCity("Paris");
        c.setCountry("FR");
        c.setGenreFamily("house & techno");
        c.setStatus("done");
        c.setQuestionBankVersion("test");
        c.setEventId(e.getId());
        c.setCreatedAt(Instant.parse("2026-09-20T10:00:00Z"));
        c.setUpdatedAt(Instant.parse("2026-09-20T10:00:00Z"));
        c = checks.save(c);
        DateCheckDate d = new DateCheckDate();
        d.setDateCheckId(c.getId());
        d.setCandidateDate(NIGHT);
        d.setVerdict("good");
        d.setRiskScore((short) 2);
        d.setOppScore((short) 6);
        d.setCoverage(new BigDecimal("0.800"));
        d.setRankOrder((short) 1);
        checkDates.save(d);

        assertThat(service.radarRerun(e.getId()).outcome()).isEqualTo(RadarOutcome.GATE_CLOSED);
        assertThat(checks.findAll()).extracting(DateCheck::getId).containsExactly(c.getId());
    }
}

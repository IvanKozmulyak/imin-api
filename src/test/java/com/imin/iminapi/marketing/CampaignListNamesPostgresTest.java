package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.dto.CampaignSummary;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The list's batched segment and event lookups (IN over UUIDs) on real Postgres. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(TestRateLimitConfig.class)
class CampaignListNamesPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired CampaignService service;
    @Autowired CampaignRepository campaigns;
    @Autowired SegmentRepository segments;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    @Test
    void pageResolvesOwnNames_andDropsForeignAndDeleted() {
        UUID orgId = org();
        UUID otherOrgId = org();
        User u = new User();
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        u.setEmail("pg-list-" + UUID.randomUUID() + "@example.com");
        AuthPrincipal owner = new AuthPrincipal(users.save(u).getId(), orgId, UserRole.OWNER, UUID.randomUUID());

        UUID own = campaign(orgId, segment(orgId, "Own seg"), event(orgId, owner, "Own night", false));
        UUID foreign = campaign(orgId, segment(otherOrgId, "Their seg"), event(otherOrgId, owner, "Their night", false));
        UUID deleted = campaign(orgId, null, event(orgId, owner, "Deleted night", true));
        UUID bare = campaign(orgId, null, null);

        Map<UUID, CampaignSummary> rows = service.list(owner, null, null, 0, 50).stream()
                .collect(Collectors.toMap(CampaignSummary::id, Function.identity()));

        assertThat(rows.get(own)).extracting(CampaignSummary::segmentName, CampaignSummary::eventName,
                CampaignSummary::eventTimezone).containsExactly("Own seg", "Own night", "Europe/Paris");
        assertThat(rows.get(foreign)).extracting(CampaignSummary::segmentName, CampaignSummary::eventName,
                CampaignSummary::eventTimezone).containsExactly(null, null, null);
        assertThat(rows.get(deleted).eventName()).isNull();
        assertThat(rows.get(bare)).extracting(CampaignSummary::segmentName, CampaignSummary::eventName)
                .containsExactly(null, null);
    }

    private UUID org() {
        Organization o = new Organization();
        o.setName("PG List Org");
        o.setSlug("pg-list-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("pg-list@example.com");
        o.setCountry("FR");
        o.setTimezone("UTC");
        return orgs.save(o).getId();
    }

    private UUID segment(UUID org, String name) {
        Segment s = new Segment();
        s.setOrgId(org);
        s.setName(name);
        s.setKind("static");
        s.setOrigin(Segment.ORIGIN_ORGANIZER);
        s.setSnapshotIds("[]");
        return segments.save(s).getId();
    }

    private UUID event(UUID org, AuthPrincipal by, String name, boolean deleted) {
        Event e = new Event();
        e.setOrgId(org);
        e.setName(name);
        e.setSlug("pg-list-event-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setStartsAt(Instant.now().plus(10, ChronoUnit.DAYS));
        e.setTimezone("Europe/Paris");
        e.setCreatedBy(by.userId());
        e.setCurrency("EUR");
        if (deleted) e.setDeletedAt(Instant.now());
        return events.save(e).getId();
    }

    private UUID campaign(UUID org, UUID segmentId, UUID eventId) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(org);
        c.setChannel("email");
        c.setName("PG list");
        c.setStatus("draft");
        c.setSegmentId(segmentId);
        c.setEventId(eventId);
        Instant now = Instant.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return campaigns.save(c).getId();
    }
}

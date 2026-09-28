package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.dto.CampaignDetailDto;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** GET /campaigns/{id} carries its segment's name (hidden origins too) and its event's timezone. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class CampaignDetailContextTest {

    @Autowired CampaignService service;
    @Autowired CampaignRepository campaigns;
    @Autowired SegmentRepository segments;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private UUID orgId;
    private UUID otherOrgId;
    private AuthPrincipal owner;

    @BeforeEach
    void setUp() {
        orgId = org();
        otherOrgId = org();
        User u = new User();
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        u.setEmail("detail-" + UUID.randomUUID() + "@example.com");
        owner = new AuthPrincipal(users.save(u).getId(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from campaigns where org_id in (?, ?)", orgId, otherOrgId);
    }

    @Test
    void hiddenPlanSegment_nameIsReturned() {
        UUID seg = segment(orgId, "Loyal guests, same genre · launch", Segment.ORIGIN_AUDIENCE_PLAN);

        CampaignDetailDto d = service.detailWithStats(owner, campaign(seg, null));

        assertThat(d.segmentId()).isEqualTo(seg);
        assertThat(d.segmentName()).isEqualTo("Loyal guests, same genre · launch");
    }

    @Test
    void segmentOfAnotherOrg_orGone_hasNoName() {
        UUID foreign = segment(otherOrgId, "Not yours", Segment.ORIGIN_ORGANIZER);

        assertThat(service.detailWithStats(owner, campaign(foreign, null)).segmentName()).isNull();
        assertThat(service.detailWithStats(owner, campaign(UUID.randomUUID(), null)).segmentName()).isNull();
    }

    @Test
    void noSegment_hasNoName() {
        assertThat(service.detailWithStats(owner, campaign(null, null)).segmentName()).isNull();
    }

    @Test
    void linkedEvent_givesItsTimezone() {
        UUID event = event("Europe/Paris");

        CampaignDetailDto d = service.detailWithStats(owner, campaign(null, event));

        assertThat(d.eventId()).isEqualTo(event);
        assertThat(d.eventTimezone()).isEqualTo("Europe/Paris");
    }

    @Test
    void anotherOrgsEvent_hasNoTimezone() {
        UUID foreign = event(otherOrgId, "Europe/Kyiv");

        assertThat(service.detailWithStats(owner, campaign(null, foreign)).eventTimezone()).isNull();
    }

    @Test
    void noEvent_hasNoTimezone() {
        assertThat(service.detailWithStats(owner, campaign(null, null)).eventTimezone()).isNull();
    }

    private UUID org() {
        Organization o = new Organization();
        o.setName("Detail Org");
        o.setSlug("detail-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("detail@example.com");
        o.setCountry("FR");
        return orgs.save(o).getId();
    }

    private UUID segment(UUID org, String name, String origin) {
        Segment s = new Segment();
        s.setOrgId(org);
        s.setName(name);
        s.setKind("static");
        s.setOrigin(origin);
        s.setSnapshotIds("[]");
        return segments.save(s).getId();
    }

    private UUID event(String zone) {
        return event(orgId, zone);
    }

    private UUID event(UUID org, String zone) {
        Event e = new Event();
        e.setOrgId(org);
        e.setName("Detail Night");
        e.setSlug("detail-event-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setStartsAt(Instant.now().plus(10, ChronoUnit.DAYS));
        e.setTimezone(zone);
        e.setCreatedBy(owner.userId());
        e.setCurrency("EUR");
        return events.save(e).getId();
    }

    private UUID campaign(UUID segmentId, UUID eventId) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Detail");
        c.setStatus("draft");
        c.setSegmentId(segmentId);
        c.setEventId(eventId);
        Instant now = Instant.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return campaigns.save(c).getId();
    }
}

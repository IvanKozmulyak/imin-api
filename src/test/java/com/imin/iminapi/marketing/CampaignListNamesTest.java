package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audience.service.SegmentService;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** GET /campaigns list rows carry their segment name and event name/zone, resolved org-scoped like detail. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class CampaignListNamesTest {

    @Autowired CampaignService service;
    @Autowired SegmentService segmentService;
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
        u.setEmail("list-names-" + UUID.randomUUID() + "@example.com");
        owner = new AuthPrincipal(users.save(u).getId(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from campaigns where org_id in (?, ?)", orgId, otherOrgId);
    }

    @Test
    void hiddenPlanSegment_nameIsReturned() {
        UUID seg = segment(orgId, "Loyal guests · launch", Segment.ORIGIN_AUDIENCE_PLAN);
        UUID id = campaign(seg, null);

        assertThat(row(id).segmentName()).isEqualTo("Loyal guests · launch");
    }

    @Test
    void segmentOfAnotherOrg_orGone_hasNoName() {
        UUID foreign = campaign(segment(otherOrgId, "Not yours", Segment.ORIGIN_ORGANIZER), null);
        UUID gone = campaign(UUID.randomUUID(), null);

        assertThat(row(foreign).segmentName()).isNull();
        assertThat(row(gone).segmentName()).isNull();
    }

    @Test
    void noSegmentNoEvent_hasNoNames() {
        UUID id = campaign(null, null);

        CampaignSummary r = row(id);
        assertThat(r.segmentName()).isNull();
        assertThat(r.eventName()).isNull();
        assertThat(r.eventTimezone()).isNull();
    }

    @Test
    void linkedEvent_givesItsNameAndTimezone() {
        UUID id = campaign(null, event(orgId, "Warehouse Night", EventStatus.LIVE, false));

        assertThat(row(id).eventName()).isEqualTo("Warehouse Night");
        assertThat(row(id).eventTimezone()).isEqualTo("Europe/Paris");
    }

    @Test
    void cancelledEvent_keepsItsName_likeDetail() {
        UUID id = campaign(null, event(orgId, "Called Off", EventStatus.CANCELLED, false));

        assertThat(row(id).eventName()).isEqualTo("Called Off");
        assertThat(row(id).eventTimezone()).isEqualTo("Europe/Paris");
    }

    @Test
    void deletedEvent_hasNoNameOrTimezone() {
        UUID id = campaign(null, event(orgId, "Deleted Night", EventStatus.LIVE, true));

        assertThat(row(id).eventName()).isNull();
        assertThat(row(id).eventTimezone()).isNull();
    }

    @Test
    void anotherOrgsEvent_hasNoNameOrTimezone() {
        UUID id = campaign(null, event(otherOrgId, "Their Night", EventStatus.LIVE, false));

        assertThat(row(id).eventName()).isNull();
        assertThat(row(id).eventTimezone()).isNull();
    }

    @Test
    void wholePage_resolvesEachRowsOwnNames() {
        UUID segA = segment(orgId, "Seg A", Segment.ORIGIN_ORGANIZER);
        UUID segB = segment(orgId, "Seg B", Segment.ORIGIN_ORGANIZER);
        UUID evA = event(orgId, "Event A", EventStatus.LIVE, false);
        UUID one = campaign(segA, evA);
        UUID two = campaign(segB, evA);
        UUID three = campaign(segA, null);

        assertThat(row(one)).extracting(CampaignSummary::segmentName, CampaignSummary::eventName)
                .containsExactly("Seg A", "Event A");
        assertThat(row(two)).extracting(CampaignSummary::segmentName, CampaignSummary::eventName)
                .containsExactly("Seg B", "Event A");
        assertThat(row(three)).extracting(CampaignSummary::segmentName, CampaignSummary::eventName)
                .containsExactly("Seg A", null);
    }

    @Test
    void namesByIds_emptyIds_isEmpty() {
        assertThat(segmentService.namesByIds(orgId, Set.of())).isEmpty();
    }

    @Test
    void namesByIds_keepsOnlyTheOrgsSegments() {
        UUID mine = segment(orgId, "Mine", Segment.ORIGIN_ORGANIZER);
        UUID theirs = segment(otherOrgId, "Theirs", Segment.ORIGIN_ORGANIZER);

        assertThat(segmentService.namesByIds(orgId, Set.of(mine, theirs)))
                .containsExactlyEntriesOf(java.util.Map.of(mine, "Mine"));
    }

    private CampaignSummary row(UUID id) {
        List<CampaignSummary> page = service.list(owner, null, null, 0, 50);
        return page.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    private UUID org() {
        Organization o = new Organization();
        o.setName("List Names Org");
        o.setSlug("list-names-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("list-names@example.com");
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

    private UUID event(UUID org, String name, EventStatus status, boolean deleted) {
        Event e = new Event();
        e.setOrgId(org);
        e.setName(name);
        e.setSlug("list-names-event-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(status);
        e.setStartsAt(Instant.now().plus(10, ChronoUnit.DAYS));
        e.setTimezone("Europe/Paris");
        e.setCreatedBy(owner.userId());
        e.setCurrency("EUR");
        if (deleted) e.setDeletedAt(Instant.now());
        return events.save(e).getId();
    }

    private UUID campaign(UUID segmentId, UUID eventId) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("List");
        c.setStatus("draft");
        c.setSegmentId(segmentId);
        c.setEventId(eventId);
        Instant now = Instant.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return campaigns.save(c).getId();
    }
}

package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.marketing.dto.CampaignDetailDto;
import com.imin.iminapi.marketing.dto.CampaignSummary;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Campaign list rows and the campaign detail carry their segment name and event name/zone, resolved org-scoped.
 * The list resolves a whole page in one batch per kind; the detail looks up its one campaign separately.
 */
@IminIntegrationTest
class CampaignListNamesTest {

    @Autowired CampaignService service;
    @Autowired SegmentService segmentService;
    @Autowired CampaignRepository campaigns;
    @Autowired SegmentRepository segments;
    @Autowired EventRepository events;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private UUID orgId;
    private UUID otherOrgId;
    private AuthPrincipal owner;

    @BeforeEach
    void setUp() {
        Organization org = fx.org();
        orgId = org.getId();
        otherOrgId = fx.org().getId();
        owner = fx.principal(fx.owner(org));
    }

    @AfterEach
    void tearDown() {
        CampaignRows.delete(jdbc, List.of(orgId, otherOrgId));
    }

    // ---- list ----

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
        UUID id = campaign(null, event(orgId, "Warehouse Night", EventStatus.LIVE, false, "Europe/Paris"));

        assertThat(row(id).eventName()).isEqualTo("Warehouse Night");
        assertThat(row(id).eventTimezone()).isEqualTo("Europe/Paris");
    }

    @Test
    void cancelledEvent_keepsItsName_likeDetail() {
        UUID id = campaign(null, event(orgId, "Called Off", EventStatus.CANCELLED, false, "Europe/Paris"));

        assertThat(row(id).eventName()).isEqualTo("Called Off");
        assertThat(row(id).eventTimezone()).isEqualTo("Europe/Paris");
    }

    @Test
    void deletedEvent_hasNoNameOrTimezone() {
        UUID id = campaign(null, event(orgId, "Deleted Night", EventStatus.LIVE, true, "Europe/Paris"));

        assertThat(row(id).eventName()).isNull();
        assertThat(row(id).eventTimezone()).isNull();
    }

    @Test
    void anotherOrgsEvent_hasNoNameOrTimezone() {
        UUID id = campaign(null, event(otherOrgId, "Their Night", EventStatus.LIVE, false, "Europe/Paris"));

        assertThat(row(id).eventName()).isNull();
        assertThat(row(id).eventTimezone()).isNull();
    }

    @Test
    void wholePage_resolvesEachRowsOwnNames() {
        UUID segA = segment(orgId, "Seg A", Segment.ORIGIN_ORGANIZER);
        UUID segB = segment(orgId, "Seg B", Segment.ORIGIN_ORGANIZER);
        UUID evA = event(orgId, "Event A", EventStatus.LIVE, false, "Europe/Paris");
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

    // ---- detail ----

    @Test
    void detail_hiddenPlanSegment_nameIsReturned() {
        UUID seg = segment(orgId, "Loyal guests, same genre · launch", Segment.ORIGIN_AUDIENCE_PLAN);

        CampaignDetailDto d = service.detailWithStats(owner, campaign(seg, null));

        assertThat(d.segmentId()).isEqualTo(seg);
        assertThat(d.segmentName()).isEqualTo("Loyal guests, same genre · launch");
    }

    @Test
    void detail_segmentOfAnotherOrg_orGone_hasNoName() {
        UUID foreign = segment(otherOrgId, "Not yours", Segment.ORIGIN_ORGANIZER);

        assertThat(service.detailWithStats(owner, campaign(foreign, null)).segmentName()).isNull();
        assertThat(service.detailWithStats(owner, campaign(UUID.randomUUID(), null)).segmentName()).isNull();
    }

    @Test
    void detail_noSegment_hasNoName() {
        assertThat(service.detailWithStats(owner, campaign(null, null)).segmentName()).isNull();
    }

    @Test
    void detail_linkedEvent_givesItsTimezone() {
        UUID event = event(orgId, "Detail Night", EventStatus.LIVE, false, "Europe/Paris");

        CampaignDetailDto d = service.detailWithStats(owner, campaign(null, event));

        assertThat(d.eventId()).isEqualTo(event);
        assertThat(d.eventTimezone()).isEqualTo("Europe/Paris");
    }

    @Test
    void detail_anotherOrgsEvent_hasNoTimezone() {
        UUID foreign = event(otherOrgId, "Detail Night", EventStatus.LIVE, false, "Europe/Kyiv");

        assertThat(service.detailWithStats(owner, campaign(null, foreign)).eventTimezone()).isNull();
    }

    @Test
    void detail_noEvent_hasNoTimezone() {
        assertThat(service.detailWithStats(owner, campaign(null, null)).eventTimezone()).isNull();
    }

    private CampaignSummary row(UUID id) {
        List<CampaignSummary> page = service.list(owner, null, null, 0, 50);
        return page.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
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

    private UUID event(UUID org, String name, EventStatus status, boolean deleted, String zone) {
        Instant now = clock.instant();
        Event e = new Event();
        e.setOrgId(org);
        e.setName(name);
        e.setSlug("list-names-event-" + UUID.randomUUID());
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(status);
        e.setStartsAt(now.plus(10, ChronoUnit.DAYS));
        e.setTimezone(zone);
        e.setCreatedBy(owner.userId());
        e.setCurrency("EUR");
        if (deleted) e.setDeletedAt(now);
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
        Instant now = clock.instant();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return campaigns.save(c).getId();
    }
}

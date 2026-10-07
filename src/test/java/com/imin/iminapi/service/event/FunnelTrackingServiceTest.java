package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.TrackRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IminIntegrationTest
class FunnelTrackingServiceTest {

    @Autowired FunnelTrackingService service;
    @Autowired FunnelEventRepository funnel;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private Event publicEvent;
    /** A session id only this test sends. */
    private String anon;

    @BeforeEach
    void setUp() {
        anon = "sess-" + UUID.randomUUID();
        Organization org = new Organization();
        org.setName("Org");
        org.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("hi@test.example");
        org.setCountry("DE");
        org = orgs.save(org);

        // events.created_by has an FK to users(id), so seed a real owner.
        User owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        publicEvent = new Event();
        publicEvent.setOrgId(org.getId());
        publicEvent.setName("Public Night");
        publicEvent.setSlug("public-" + UUID.randomUUID().toString().substring(0, 8));
        publicEvent.setVisibility(EventVisibility.PUBLIC);
        publicEvent.setStatus(EventStatus.LIVE);
        publicEvent.setStartsAt(Instant.now().plusSeconds(86_400));
        publicEvent.setCreatedBy(owner.getId());
        publicEvent.setCurrency("EUR");
        publicEvent = events.save(publicEvent);
    }

    @AfterEach
    void tearDown() {
        if (publicEvent != null) OrgRows.delete(jdbc, List.of(publicEvent.getOrgId()));
    }

    /** This test's rows: its own event, or its own session id on any event. */
    private List<FunnelEvent> ownRows() {
        return funnel.findAll().stream()
                .filter(r -> r.getEventId().equals(publicEvent.getId()) || anon.equals(r.getAnonId()))
                .toList();
    }

    @Test
    void records_a_page_view_for_a_public_event() {
        service.track(publicEvent.getId(), new TrackRequest("PAGE_VIEW", anon));
        assertThat(ownRows()).hasSize(1);
        assertThat(ownRows().get(0).getStage()).isEqualTo(FunnelEvent.STAGE_PAGE_VIEW);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "unknown event, false, PAGE_VIEW, false",
            "unknown stage, true, BOGUS, false",
            "blank anon id, true, PAGE_VIEW, true"
    })
    void a_bad_beacon_is_a_noop(String name, boolean ownEvent, String stage, boolean blankAnon) {
        UUID eventId = ownEvent ? publicEvent.getId() : UUID.randomUUID();
        service.track(eventId, new TrackRequest(stage, blankAnon ? "  " : anon));

        assertThat(ownRows()).isEmpty();
    }

    @Test
    void checkout_start_is_recorded_with_anon_id_trimmed_and_capped_to_64() {
        String noisy = "  " + "x".repeat(100) + "  ";
        service.track(publicEvent.getId(), new TrackRequest("CHECKOUT_START", noisy));

        var rows = ownRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStage()).isEqualTo(FunnelEvent.STAGE_CHECKOUT_START);
        assertThat(rows.get(0).getAnonId()).isEqualTo("x".repeat(64));
    }

    /**
     * The client label (V93). It has to exist before the app ships:
     * {@code /analytics/attribution} is live and organizer-facing, so unlabelled
     * app traffic would merge into web "direct" and quietly make shipped
     * conversion numbers wrong.
     */
    @Test
    void records_the_client_label_normalised() {
        service.track(publicEvent.getId(), new TrackRequest(
                "PAGE_VIEW", anon, null, null, null, null, null, "  IOS "));

        assertThat(ownRows()).singleElement().extracting(FunnelEvent::getClient).isEqualTo("ios");
    }

    /**
     * A beacon from before the app — and every web beacon — stays null. An unrecognised label is
     * dropped rather than stored, or a typo'd client shows up as its own row in attribution.
     */
    @ParameterizedTest(name = "client [{0}] stays null")
    @CsvSource(nullValues = "NULL", value = {"NULL", "windows-phone"})
    void an_absent_or_unknown_client_stays_null(String client) {
        service.track(publicEvent.getId(), new TrackRequest(
                "PAGE_VIEW", anon, null, null, null, null, null, client));

        assertThat(ownRows()).singleElement().extracting(FunnelEvent::getClient).isNull();
    }

    /**
     * The client label must never touch {@code utm_source}: the shipped auto-tag
     * feature already writes that field, and colliding with it would corrupt
     * live campaign attribution.
     */
    @Test
    void the_client_label_never_lands_in_utm_source() {
        service.track(publicEvent.getId(), new TrackRequest(
                "PAGE_VIEW", anon, "instagram", null, null, null, null, "android"));

        var row = ownRows().get(0);
        assertThat(row.getUtmSource()).isEqualTo("instagram");
        assertThat(row.getClient()).isEqualTo("android");
    }
}

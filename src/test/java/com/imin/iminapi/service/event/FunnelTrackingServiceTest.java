package com.imin.iminapi.service.event;

import com.imin.iminapi.config.TestRateLimitConfig;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class FunnelTrackingServiceTest {

    @Autowired FunnelTrackingService service;
    @Autowired FunnelEventRepository funnel;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    private Event publicEvent;

    @BeforeEach
    void setUp() {
        wipe();
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
    void tearDown() { wipe(); }

    private void wipe() { funnel.deleteAll(); events.deleteAll(); users.deleteAll(); orgs.deleteAll(); }

    @Test
    void records_a_page_view_for_a_public_event() {
        service.track(publicEvent.getId(), new TrackRequest("PAGE_VIEW", "sess-1"));
        assertThat(funnel.findAll()).hasSize(1);
        assertThat(funnel.findAll().get(0).getStage()).isEqualTo(FunnelEvent.STAGE_PAGE_VIEW);
    }

    @Test
    void unknown_event_is_a_noop() {
        service.track(UUID.randomUUID(), new TrackRequest("PAGE_VIEW", "sess-1"));
        assertThat(funnel.findAll()).isEmpty();
    }

    @Test
    void unknown_stage_is_a_noop() {
        service.track(publicEvent.getId(), new TrackRequest("BOGUS", "sess-1"));
        assertThat(funnel.findAll()).isEmpty();
    }

    @Test
    void blank_anon_id_is_a_noop() {
        service.track(publicEvent.getId(), new TrackRequest("PAGE_VIEW", "  "));
        assertThat(funnel.findAll()).isEmpty();
    }

    @Test
    void checkout_start_is_recorded_with_anon_id_trimmed_and_capped_to_64() {
        String noisy = "  " + "x".repeat(100) + "  ";
        service.track(publicEvent.getId(), new TrackRequest("CHECKOUT_START", noisy));

        var rows = funnel.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStage()).isEqualTo(FunnelEvent.STAGE_CHECKOUT_START);
        assertThat(rows.get(0).getAnonId()).isEqualTo("x".repeat(64));
    }
}

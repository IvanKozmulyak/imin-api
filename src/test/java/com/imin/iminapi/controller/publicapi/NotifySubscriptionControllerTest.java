package com.imin.iminapi.controller.publicapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test for {@code POST /api/v1/public/events/{id}/notify}.
 *
 * Uses the real {@link com.imin.iminapi.service.event.NotifySubscriptionService} and
 * the H2-backed JPA layer (no mocks) so the unique-constraint idempotency path is
 * actually exercised. Each test cleans up its own rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class NotifySubscriptionControllerTest {

    @Autowired MockMvc mvc;
    @Autowired EventRepository eventRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired NotifySubscriptionRepository subscriptionRepository;
    @Autowired TicketTierRepository ticketTierRepository;

    final ObjectMapper om = new ObjectMapper();

    Organization org;
    User owner;

    @BeforeEach
    void setUp() {
        // Clear in FK-safe order to start each test clean.
        subscriptionRepository.deleteAll();
        ticketTierRepository.deleteAll();
        eventRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        org = new Organization();
        org.setName("Notify Test Org");
        org.setSlug("notify-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("notify-org@example.com");
        org.setCountry("DE");
        org = organizationRepository.save(org);

        owner = new User();
        owner.setEmail("notify-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = userRepository.save(owner);
    }

    @AfterEach
    void tearDown() {
        // SpringBootTest doesn't roll back like @DataJpaTest does — clean up so
        // sibling tests (e.g. PublicEventServiceListTest) don't see our rows.
        subscriptionRepository.deleteAll();
        ticketTierRepository.deleteAll();
        eventRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    private Event saveEvent(EventStatus status, EventVisibility visibility, Instant publishedAt,
                            Instant deletedAt) {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Notify Event");
        e.setSlug("notify-event-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(visibility);
        e.setStatus(status);
        e.setPublishedAt(publishedAt);
        e.setDeletedAt(deletedAt);
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        return eventRepository.save(e);
    }

    private Event publicLiveEvent() {
        return saveEvent(EventStatus.LIVE, EventVisibility.PUBLIC,
                Instant.now().minusSeconds(3600), null);
    }

    // -----------------------------------------------------------------------
    // (a) 200 + subscription row on first call
    // -----------------------------------------------------------------------
    @Test
    void subscribe_returns200AndPersistsRow_onFirstCall() throws Exception {
        Event e = publicLiveEvent();

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "ada@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(true));

        List<NotifySubscription> rows = subscriptionRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEventId()).isEqualTo(e.getId());
        assertThat(rows.get(0).getEmail()).isEqualTo("ada@example.com");
    }

    // -----------------------------------------------------------------------
    // (b) 200 on duplicate same-email submission — single row preserved
    // -----------------------------------------------------------------------
    @Test
    void subscribe_returns200ForDuplicateSameEmail_singleRowPreserved() throws Exception {
        Event e = publicLiveEvent();
        String body = om.writeValueAsString(Map.of("email", "ada@example.com"));

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(true));

        assertThat(subscriptionRepository.findAll()).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // (c) 200 on duplicate different-case-email — single row preserved (lowercased)
    // -----------------------------------------------------------------------
    @Test
    void subscribe_returns200ForDuplicateDifferentCase_singleRowPreserved() throws Exception {
        Event e = publicLiveEvent();

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "Ada@Example.com"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "ADA@example.COM"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "ada@example.com"))))
                .andExpect(status().isOk());

        List<NotifySubscription> rows = subscriptionRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEmail()).isEqualTo("ada@example.com");
    }

    // -----------------------------------------------------------------------
    // (d) 400 INVALID_REQUEST on bad email — fields map populated, no row inserted
    // -----------------------------------------------------------------------
    @Test
    void subscribe_returns400InvalidRequest_onMalformedEmail() throws Exception {
        Event e = publicLiveEvent();

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "not-an-email"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.fields.email").exists());

        assertThat(subscriptionRepository.findAll()).isEmpty();
    }

    @Test
    void subscribe_returns400InvalidRequest_onMissingEmailField() throws Exception {
        Event e = publicLiveEvent();

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.fields.email").exists());
    }

    @Test
    void subscribe_returns400InvalidRequest_onBlankEmail() throws Exception {
        Event e = publicLiveEvent();

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.fields.email").exists());
    }

    // -----------------------------------------------------------------------
    // (e) 404 NOT_FOUND on draft / private / deleted event
    // -----------------------------------------------------------------------
    @Test
    void subscribe_returns404_onDraftEvent() throws Exception {
        Event e = saveEvent(EventStatus.DRAFT, EventVisibility.PUBLIC, null, null);

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "ada@example.com"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(subscriptionRepository.findAll()).isEmpty();
    }

    @Test
    void subscribe_returns404_onPrivateEvent() throws Exception {
        Event e = saveEvent(EventStatus.LIVE, EventVisibility.PRIVATE,
                Instant.now().minusSeconds(60), null);

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "ada@example.com"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(subscriptionRepository.findAll()).isEmpty();
    }

    @Test
    void subscribe_returns404_onSoftDeletedEvent() throws Exception {
        Event e = saveEvent(EventStatus.LIVE, EventVisibility.PUBLIC,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(60));

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "ada@example.com"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(subscriptionRepository.findAll()).isEmpty();
    }

    @Test
    void subscribe_returns404_onUnknownEventId() throws Exception {
        mvc.perform(post("/api/v1/public/events/" + UUID.randomUUID() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "ada@example.com"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // -----------------------------------------------------------------------
    // Bonus: two different events can each have the same email (per-event uniqueness)
    // -----------------------------------------------------------------------
    @Test
    void subscribe_sameEmailOnTwoDifferentEvents_createsTwoRows() throws Exception {
        Event a = publicLiveEvent();
        Event b = publicLiveEvent();

        String body = om.writeValueAsString(Map.of("email", "ada@example.com"));
        mvc.perform(post("/api/v1/public/events/" + a.getId() + "/notify")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/public/events/" + b.getId() + "/notify")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());

        assertThat(subscriptionRepository.findAll()).hasSize(2);
    }

    // -----------------------------------------------------------------------
    // Bonus: endpoint reachable without auth (sanity)
    // -----------------------------------------------------------------------
    @Test
    void subscribe_endpointReachableWithoutAuth() throws Exception {
        Event e = publicLiveEvent();

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", "ada@example.com"))))
                .andExpect(status().isOk());
    }
}

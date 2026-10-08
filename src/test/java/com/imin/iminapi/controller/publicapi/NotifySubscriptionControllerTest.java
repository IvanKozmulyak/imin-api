package com.imin.iminapi.controller.publicapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.service.event.NotifySubscriptionService;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test for {@code POST /api/v1/public/events/{id}/notify}, over the real
 * {@link NotifySubscriptionService} on Postgres so the unique-constraint idempotency path runs.
 */
@IminIntegrationTest
class NotifySubscriptionControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventRepository eventRepository;
    @Autowired NotifySubscriptionRepository subscriptionRepository;

    final ObjectMapper om = new ObjectMapper();

    Organization org;
    User owner;
    String ada;

    @BeforeEach
    void setUp() {
        org = fx.org();
        owner = fx.owner(org);
        ada = fx.email("ada");
    }

    private Event saveEvent(EventStatus status, EventVisibility visibility, Instant publishedAt,
                            Instant deletedAt) {
        Event e = fx.event(org, owner, status, null);
        e.setVisibility(visibility);
        e.setPublishedAt(publishedAt);
        e = eventRepository.save(e);
        // deleted_at is insert-only on the entity; production writes it with a bulk update too.
        if (deletedAt != null) {
            jdbc.update("UPDATE events SET deleted_at = ? WHERE id = ?", Timestamp.from(deletedAt), e.getId());
        }
        return e;
    }

    private Event publicLiveEvent() {
        return saveEvent(EventStatus.LIVE, EventVisibility.PUBLIC,
                clock.instant().minusSeconds(3600), null);
    }

    /** This event's rows only; the table is shared with every other test. */
    private List<NotifySubscription> rows(UUID eventId) {
        List<UUID> ids = jdbc.queryForList(
                "SELECT id FROM notify_subscriptions WHERE event_id = ?", UUID.class, eventId);
        return subscriptionRepository.findAllById(ids);
    }

    private String body(Map<String, ?> fields) throws Exception {
        return om.writeValueAsString(fields);
    }

    // -----------------------------------------------------------------------
    // (a) 200 + subscription row on first call
    // -----------------------------------------------------------------------
    @Test
    void subscribe_returns200AndPersistsRow_onFirstCall() throws Exception {
        Event e = publicLiveEvent();

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", ada))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(true));

        List<NotifySubscription> rows = rows(e.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEventId()).isEqualTo(e.getId());
        assertThat(rows.get(0).getEmail()).isEqualTo(ada);
    }

    // -----------------------------------------------------------------------
    // (b) 200 on duplicate same-email submission — single row preserved
    // -----------------------------------------------------------------------
    @Test
    void subscribe_returns200ForDuplicateSameEmail_singleRowPreserved() throws Exception {
        Event e = publicLiveEvent();
        String body = body(Map.of("email", ada));

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(true));

        assertThat(rows(e.getId())).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // (c) 200 on duplicate different-case-email — single row preserved (lowercased)
    // -----------------------------------------------------------------------
    @Test
    void subscribe_returns200ForDuplicateDifferentCase_singleRowPreserved() throws Exception {
        Event e = publicLiveEvent();
        String capitalised = Character.toUpperCase(ada.charAt(0)) + ada.substring(1);

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", capitalised))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", ada.toUpperCase()))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", ada))))
                .andExpect(status().isOk());

        List<NotifySubscription> rows = rows(e.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEmail()).isEqualTo(ada);
    }

    // -----------------------------------------------------------------------
    // (c2) re-subscribing after a release notification RE-ARMS the row
    // -----------------------------------------------------------------------
    @Test
    void subscribe_reArmsRow_whenAlreadyNotified() throws Exception {
        Event e = publicLiveEvent();
        String body = body(Map.of("email", ada));

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        // Simulate NotifyReleaseSender having already mailed this subscriber.
        NotifySubscription row = rows(e.getId()).get(0);
        row.setNotifiedAt(clock.instant());
        subscriptionRepository.save(row);

        // The buyer signs up again — they want to hear about the NEXT release, so the
        // UNIQUE pre-check must not silently eat the request.
        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribed").value(true));

        List<NotifySubscription> rows = rows(e.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getNotifiedAt()).isNull();
    }

    // -----------------------------------------------------------------------
    // (c3) consent provenance (V77) — captured on first subscribe
    // -----------------------------------------------------------------------
    @Test
    void subscribe_capturesConsentProvenance() throws Exception {
        Event e = publicLiveEvent();

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .header("X-Forwarded-For", "203.0.113.7, 70.41.3.18")
                        .header("User-Agent", "Mozilla/5.0 (iPhone)")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", ada, "locale", "ES"))))
                .andExpect(status().isOk());

        NotifySubscription row = rows(e.getId()).get(0);
        // NOT the raw X-Forwarded-For hop the request supplied. This value is
        // consent evidence, and evidence the subject of the record can dictate is
        // worth nothing — anyone could write any address, including someone
        // else's, into the trail. It is the resolved remote address, which behind
        // Railway is the real client (server.forward-headers-strategy: framework)
        // and here is MockMvc's own.
        assertThat(row.getSourceIp()).isEqualTo("127.0.0.1");
        assertThat(row.getUserAgent()).isEqualTo("Mozilla/5.0 (iPhone)");
        // Proof-of-consent is the exact wording the form shows.
        assertThat(row.getConsentText()).isEqualTo(NotifySubscriptionService.CONSENT_TEXT);
        assertThat(row.getConsentText()).isEqualTo("We'll email you if tickets release.");
        // Locale is normalized (trimmed + lowercased), not stored raw.
        assertThat(row.getLocale()).isEqualTo("es");
    }

    @Test
    void subscribe_fallsBackToRemoteAddr_whenNoForwardedForHeader() throws Exception {
        Event e = publicLiveEvent();

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", ada))))
                .andExpect(status().isOk());

        // MockMvc's default remote address.
        assertThat(rows(e.getId()).get(0).getSourceIp()).isEqualTo("127.0.0.1");
    }

    @Test
    void subscribe_truncatesOverlongUserAgent_ratherThanFailing() throws Exception {
        Event e = publicLiveEvent();
        String hostileUa = "U".repeat(4000);

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .header("User-Agent", hostileUa)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", ada))))
                .andExpect(status().isOk());

        assertThat(rows(e.getId()).get(0).getUserAgent()).hasSize(255);
    }

    @Test
    void subscribe_storesNullLocale_whenUnsupportedOrAbsent() throws Exception {
        Event e = publicLiveEvent();

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", fx.email("junk"), "locale", "kl"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", fx.email("absent")))))
                .andExpect(status().isOk());

        assertThat(rows(e.getId()))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.getLocale()).isNull());
    }

    @Test
    void subscribe_refreshesProvenance_onReArm() throws Exception {
        Event e = publicLiveEvent();

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .header("X-Forwarded-For", "203.0.113.7")
                        .header("User-Agent", "OldBrowser/1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", ada, "locale", "es"))))
                .andExpect(status().isOk());

        NotifySubscription row = rows(e.getId()).get(0);
        row.setNotifiedAt(clock.instant());
        subscriptionRepository.save(row);

        // Same buyer, new device, new language, months later — the provenance must
        // describe THIS act of consent, not the stale one.
        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .header("X-Forwarded-For", "198.51.100.22")
                        .header("User-Agent", "NewBrowser/9.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", ada, "locale", "uk"))))
                .andExpect(status().isOk());

        List<NotifySubscription> rows = rows(e.getId());
        assertThat(rows).hasSize(1);
        NotifySubscription reArmed = rows.get(0);
        assertThat(reArmed.getNotifiedAt()).isNull();
        assertThat(reArmed.getSourceIp()).isEqualTo("127.0.0.1");
        assertThat(reArmed.getUserAgent()).isEqualTo("NewBrowser/9.0");
        assertThat(reArmed.getLocale()).isEqualTo("uk");
        assertThat(reArmed.getConsentText()).isEqualTo(NotifySubscriptionService.CONSENT_TEXT);
    }

    @Test
    void subscribe_leavesNotifiedAtNull_onFirstCall() throws Exception {
        Event e = publicLiveEvent();

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", ada))))
                .andExpect(status().isOk());

        assertThat(rows(e.getId()).get(0).getNotifiedAt()).isNull();
    }

    // -----------------------------------------------------------------------
    // (d) 400 INVALID_REQUEST on bad email — fields map populated, no row inserted
    // -----------------------------------------------------------------------
    @Test
    void subscribe_returns400InvalidRequest_onMalformedEmail() throws Exception {
        Event e = publicLiveEvent();

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", "not-an-email"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.fields.email").exists());

        assertThat(rows(e.getId())).isEmpty();
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
                        .content(body(Map.of("email", "   "))))
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
                        .content(body(Map.of("email", ada))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(rows(e.getId())).isEmpty();
    }

    @Test
    void subscribe_returns404_onPrivateEvent() throws Exception {
        Event e = saveEvent(EventStatus.LIVE, EventVisibility.PRIVATE,
                clock.instant().minusSeconds(60), null);

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", ada))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(rows(e.getId())).isEmpty();
    }

    @Test
    void subscribe_returns404_onSoftDeletedEvent() throws Exception {
        Event e = saveEvent(EventStatus.LIVE, EventVisibility.PUBLIC,
                clock.instant().minusSeconds(3600), clock.instant().minusSeconds(60));

        mvc.perform(post("/api/v1/public/events/" + e.getId() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", ada))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        assertThat(rows(e.getId())).isEmpty();
    }

    @Test
    void subscribe_returns404_onUnknownEventId() throws Exception {
        mvc.perform(post("/api/v1/public/events/" + UUID.randomUUID() + "/notify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", ada))))
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

        String body = body(Map.of("email", ada));
        mvc.perform(post("/api/v1/public/events/" + a.getId() + "/notify")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/public/events/" + b.getId() + "/notify")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());

        assertThat(rows(a.getId())).hasSize(1);
        assertThat(rows(b.getId())).hasSize(1);
    }
}

package com.imin.iminapi.checkoutreminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.checkoutreminder.config.CheckoutReminderProperties;
import com.imin.iminapi.checkoutreminder.repository.CheckoutReminderRepository;
import com.imin.iminapi.checkoutreminder.repository.OrgCheckoutReminderSettingRepository;
import com.imin.iminapi.checkoutreminder.service.CheckoutReminderSchedule;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.service.event.InventoryService;
import com.imin.iminapi.stripe.StripeProperties;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PgFaults;
import com.imin.iminapi.support.PropertyFlips;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Only a proven tick on an expired web checkout is kept, and keeping it never holds a seat. */
@IminIntegrationTest
class CheckoutReminderCaptureTest {

    private static final String SECRET = "whsec_checkout_reminder_capture";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired StripeProperties stripeProps;
    @Autowired CheckoutReminderProperties props;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired OrganizationRepository orgs;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired CheckoutReminderRepository reminders;
    @Autowired OrgCheckoutReminderSettingRepository settings;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;

    private ReminderRows rows;
    private ReminderRows.Org org;
    private Event night;
    private TicketTier tier;

    @BeforeEach
    void setUp() {
        flips.set(stripeProps, "webhookSecretV1", SECRET);
        flips.set(props, "enabled", true);
        rows = new ReminderRows(fx, orgs, events, reminders, settings, consumers, memberships, jdbc);
        org = rows.org(Instant.now());
        night = rows.night(org, Instant.now().plus(Duration.ofDays(10)));
        tier = rows.tier(night, 1600, 50);
    }

    @AfterEach
    void cleanUp() {
        rows.deleteOwn();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"tickedProvenAllowlisted", "noTick", "tickWithoutProof", "genericLabelWithoutVersion",
            "versionNotOnAllowlist", "sentenceNamesAnotherOrganizer", "noAddressOnTheSession", "orgOff", "platformOff",
            "nativeClient"})
    void onlyAProvenTickOnAnExpiredWebSessionIsKept(String variant) throws Exception {
        String email = rows.email("capture");
        UUID reservation = inventory.reserve(tier.getId(), 2, Instant.now().plus(Duration.ofMinutes(30)), null);
        Map<String, String> md = ticked(reservation, tier, 2);
        md.put("buyer_locale", "fr");
        md.put("promo_id", UUID.randomUUID().toString());
        switch (variant) {
            case "tickedProvenAllowlisted" -> { }
            // The box unticked; the sentence and version alone are no consent.
            case "noTick" -> md.put("marketing_opt_in", "false");
            case "tickWithoutProof" -> {
                md.remove("marketing_opt_in_proof");
                md.remove("marketing_opt_in_text_version");
            }
            case "genericLabelWithoutVersion" -> {
                md.put("marketing_opt_in_proof", "Email me about events by this organizer. I agree to receive "
                        + "email marketing and can unsubscribe any time, one click in every email.");
                md.remove("marketing_opt_in_text_version");
            }
            case "versionNotOnAllowlist" -> md.put("marketing_opt_in_text_version", "checkout-generic-2026-09");
            case "sentenceNamesAnotherOrganizer" ->
                    md.put("marketing_opt_in_proof", ReminderRows.LABEL.formatted("Some Other Club"));
            case "orgOff" -> settings.disable(org.id(), Instant.now());
            case "platformOff" -> flips.set(props, "enabled", false);
            case "nativeClient" -> md.put("client", "native");
            case "noAddressOnTheSession" -> email = null;
            default -> throw new IllegalArgumentException(variant);
        }
        String session = "cs_test_cr_" + UUID.randomUUID();
        long created = Instant.now().minus(Duration.ofMinutes(31)).getEpochSecond();

        expire(session, created, email == null ? null : email.toUpperCase(), md);

        assertThat(reservationStatus(reservation)).isEqualTo("RELEASED");
        assertThat(tiers.findById(tier.getId()).orElseThrow().getReserved()).isZero();
        List<Map<String, Object>> kept = jdbc.queryForList(
                "SELECT * FROM checkout_reminders WHERE stripe_session_id = ?", session);
        if (!variant.equals("tickedProvenAllowlisted")) {
            assertThat(kept).isEmpty();
            return;
        }
        assertThat(kept).hasSize(1);
        Map<String, Object> row = kept.get(0);
        Instant startedAt = Instant.ofEpochSecond(created);
        assertThat(row.get("org_id")).isEqualTo(org.id());
        assertThat(row.get("event_id")).isEqualTo(night.getId());
        assertThat(row.get("tier_id")).isEqualTo(tier.getId());
        assertThat(row.get("qty")).isEqualTo(2);
        assertThat(row.get("email_normalized")).isEqualTo(email);
        assertThat(row.get("buyer_locale")).isEqualTo("fr");
        assertThat(row.get("promo_used")).isEqualTo(true);
        assertThat(row.get("consent_proof")).isEqualTo(ReminderRows.LABEL.formatted(org.org().getName()));
        assertThat(row.get("consent_text_version")).isEqualTo(ReminderRows.VERSION);
        assertThat(((Timestamp) row.get("started_at")).toInstant()).isEqualTo(startedAt);
        assertThat(((Timestamp) row.get("due_at")).toInstant())
                .isEqualTo(CheckoutReminderSchedule.dueAt(startedAt, "Europe/Paris", Duration.ofMinutes(60)));
        assertThat(row.get("status")).isEqualTo("PENDING");
    }

    @Test
    void aReminderStorageFailureNeverKeepsSeatsHeld() throws Exception {
        String email = rows.email("capture-fault");
        UUID reservation = inventory.reserve(tier.getId(), 3, Instant.now().plus(Duration.ofMinutes(30)), null);
        String session = "cs_test_cr_" + UUID.randomUUID();

        try (PgFaults.Fault ignored = PgFaults.failWrites(jdbc, "checkout_reminders", "event_id", night.getId())) {
            expire(session, Instant.now().minus(Duration.ofMinutes(31)).getEpochSecond(), email,
                    ticked(reservation, tier, 3));
        }

        assertThat(reservationStatus(reservation)).isEqualTo("RELEASED");
        assertThat(tiers.findById(tier.getId()).orElseThrow().getReserved()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM checkout_reminders WHERE stripe_session_id = ?",
                Integer.class, session)).isZero();
    }

    @Test
    void oneReminderPerPersonPerNight() throws Exception {
        String email = rows.email("capture-once");
        TicketTier vip = rows.tier(night, 4000, 20);
        long t0 = Instant.now().minus(Duration.ofMinutes(50)).getEpochSecond();
        String first = "cs_test_cr_a_" + UUID.randomUUID();
        String second = "cs_test_cr_b_" + UUID.randomUUID();
        String third = "cs_test_cr_c_" + UUID.randomUUID();

        expire(first, t0, email, ticked(inventory.reserve(tier.getId(), 2, Instant.now().plusSeconds(1800), null), tier, 2));
        expire(second, t0 + 300, email, ticked(inventory.reserve(vip.getId(), 3, Instant.now().plusSeconds(1800), null), vip, 3));

        List<Map<String, Object>> afterSecond = rowsFor(email);
        assertThat(afterSecond).hasSize(1);
        assertThat(afterSecond.get(0).get("stripe_session_id")).isEqualTo(second);
        assertThat(afterSecond.get(0).get("tier_id")).isEqualTo(vip.getId());
        assertThat(afterSecond.get(0).get("qty")).isEqualTo(3);
        assertThat(((Timestamp) afterSecond.get(0).get("started_at")).toInstant()).isEqualTo(Instant.ofEpochSecond(t0 + 300));

        jdbc.update("UPDATE checkout_reminders SET status = 'SENT', sent_at = now() WHERE stripe_session_id = ?", second);
        expire(third, t0 + 600, email, ticked(inventory.reserve(tier.getId(), 1, Instant.now().plusSeconds(1800), null), tier, 1));

        List<Map<String, Object>> afterThird = rowsFor(email);
        assertThat(afterThird).hasSize(1);
        assertThat(afterThird.get(0).get("stripe_session_id")).isEqualTo(second);
        assertThat(afterThird.get(0).get("status")).isEqualTo("SENT");
        assertThat(afterThird.get(0).get("qty")).isEqualTo(3);
    }

    @Test
    void anOlderExpiryArrivingLateNeverReplacesTheNewerCheckout() throws Exception {
        String email = rows.email("capture-order");
        TicketTier vip = rows.tier(night, 4000, 20);
        long t0 = Instant.now().minus(Duration.ofMinutes(50)).getEpochSecond();
        String older = "cs_test_cr_old_" + UUID.randomUUID();
        String newer = "cs_test_cr_new_" + UUID.randomUUID();

        // Stripe does not promise order: the newer session's expiry lands first.
        expire(newer, t0 + 300, email, ticked(inventory.reserve(vip.getId(), 3, Instant.now().plusSeconds(1800), null), vip, 3));
        expire(older, t0, email, ticked(inventory.reserve(tier.getId(), 2, Instant.now().plusSeconds(1800), null), tier, 2));

        List<Map<String, Object>> kept = rowsFor(email);
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).get("stripe_session_id")).isEqualTo(newer);
        assertThat(kept.get(0).get("tier_id")).isEqualTo(vip.getId());
        assertThat(kept.get(0).get("qty")).isEqualTo(3);
        assertThat(((Timestamp) kept.get(0).get("started_at")).toInstant()).isEqualTo(Instant.ofEpochSecond(t0 + 300));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Map<String, String> ticked(UUID reservation, TicketTier t, int qty) {
        Map<String, String> md = new LinkedHashMap<>();
        md.put("reservation_id", reservation.toString());
        md.put("tier_id", t.getId().toString());
        md.put("qty", String.valueOf(qty));
        md.put("event_id", night.getId().toString());
        md.put("client", "web");
        md.put("marketing_opt_in", "true");
        md.put("marketing_opt_in_proof", ReminderRows.LABEL.formatted(org.org().getName()));
        md.put("marketing_opt_in_text_version", ReminderRows.VERSION);
        return md;
    }

    private void expire(String sessionId, long created, String email, Map<String, String> metadata) throws Exception {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", sessionId);
        session.put("object", "checkout.session");
        session.put("created", created);
        session.put("customer_email", email);
        session.put("metadata", metadata);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", "evt_cr_" + UUID.randomUUID().toString().replace("-", ""));
        event.put("object", "event");
        event.put("type", "checkout.session.expired");
        event.put("api_version", "2026-04-22.dahlia");
        event.put("created", Instant.now().getEpochSecond());
        event.put("data", Map.of("object", session));
        String body = JSON.writeValueAsString(event);
        long ts = Instant.now().getEpochSecond();
        String sig = "t=" + ts + ",v1=" + Webhook.Util.computeHmacSha256(SECRET, ts + "." + body);
        mvc.perform(post("/api/v1/stripe/webhook/v1")
                        .contentType("application/json; charset=utf-8")
                        .content(body.getBytes(StandardCharsets.UTF_8))
                        .header("Stripe-Signature", sig))
                .andExpect(status().isOk());
    }

    private String reservationStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM ticket_reservations WHERE id = ?", String.class, id);
    }

    private List<Map<String, Object>> rowsFor(String email) {
        return jdbc.queryForList("SELECT * FROM checkout_reminders WHERE event_id = ? AND email_normalized = ?",
                night.getId(), email);
    }
}

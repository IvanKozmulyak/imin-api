package com.imin.iminapi.checkoutreminder;

import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.checkoutreminder.config.CheckoutReminderProperties;
import com.imin.iminapi.checkoutreminder.repository.CheckoutReminderRepository;
import com.imin.iminapi.checkoutreminder.repository.OrgCheckoutReminderSettingRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundReason;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.refund.RefundStatus;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import com.imin.iminapi.support.RecordingRateLimiter;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may switch the reminder on, and dashboard numbers that add up for this org only, with no buyer address. */
@IminIntegrationTest
class CheckoutReminderControllerTest {

    private static final String URL = "/api/v1/marketing/checkout-reminders";

    @Autowired MockMvc mvc;
    @Autowired CheckoutReminderProperties props;
    @Autowired PropertyFlips flips;
    @Autowired AuditRows audit;
    @Autowired IminFixtures fx;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired CheckoutReminderRepository reminders;
    @Autowired OrgCheckoutReminderSettingRepository settings;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecordingRateLimiter limiter;
    @Autowired MarketingEmailProperties emailProps;
    @Autowired CampaignEmailProvider provider;

    private ReminderRows rows;
    private ReminderRows.Org org;

    @BeforeEach
    void setUp() {
        flips.set(props, "enabled", true);
        rows = new ReminderRows(fx, orgs, events, reminders, settings, consumers, memberships, jdbc);
        org = rows.org(Instant.now());
        settings.disable(org.id(), Instant.now());
    }

    @AfterEach
    void cleanUp() {
        rows.deleteOwn();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"member", "adminWithoutLegalIdentity", "adminWithLegalIdentity", "platformOff",
            "adminTurnsItOff"})
    void turningOnNeedsAdminAndLegalIdentity(String variant) throws Exception {
        User admin = user(org.org(), UserRole.ADMIN);
        switch (variant) {
            case "member" -> {
                mvc.perform(put(URL).with(as(user(org.org(), UserRole.MEMBER))).contentType("application/json")
                                .content("{\"enabled\":true}"))
                        .andExpect(status().isForbidden());
                assertThat(settings.isEnabled(org.id())).isFalse();
            }
            case "adminWithoutLegalIdentity" -> {
                Organization o = orgs.findById(org.id()).orElseThrow();
                o.setLegalName(null);
                o.setLegalContact(" ");
                orgs.save(o);
                mvc.perform(put(URL).with(as(admin)).contentType("application/json").content("{\"enabled\":true}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                        .andExpect(jsonPath("$.error.fields.legalName").value("required"))
                        .andExpect(jsonPath("$.error.fields.legalContact").value("required"));
                assertThat(settings.isEnabled(org.id())).isFalse();
            }
            case "adminWithLegalIdentity" -> {
                mvc.perform(put(URL).with(as(admin)).contentType("application/json").content("{\"enabled\":true}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.enabled").value(true))
                        .andExpect(jsonPath("$.canEdit").value(true));
                assertThat(settings.isEnabled(org.id())).isTrue();
                assertThat(settings.findById(org.id()).orElseThrow().getEnabledBy()).isEqualTo(admin.getId());
                audit.assertRecorded(org.id(), AuditActions.CHECKOUT_REMINDER_ENABLED, "organization", org.id());
            }
            case "platformOff" -> {
                flips.set(props, "enabled", false);
                mvc.perform(put(URL).with(as(admin)).contentType("application/json").content("{\"enabled\":true}"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
                assertThat(settings.isEnabled(org.id())).isFalse();
            }
            case "adminTurnsItOff" -> {
                settings.enable(org.id(), admin.getId(), Instant.now());
                Event night = rows.night(org, Instant.now().plus(Duration.ofDays(4)));
                TicketTier tier = rows.tier(night, 1600, 50);
                UUID waiting = rows.waiting(org, night, tier, rows.email("off"), 1, "en", false,
                        Instant.now().minus(Duration.ofMinutes(20)), Instant.now().plus(Duration.ofMinutes(40)));
                mvc.perform(put(URL).with(as(admin)).contentType("application/json").content("{\"enabled\":false}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.enabled").value(false));
                assertThat(settings.isEnabled(org.id())).isFalse();
                assertThat(rows.load(waiting).getStatus().name()).isEqualTo("NOT_SENT");
                assertThat(rows.load(waiting).getReason().name()).isEqualTo("DISABLED");
                audit.assertRecorded(org.id(), AuditActions.CHECKOUT_REMINDER_DISABLED, "organization", org.id());
            }
            default -> throw new IllegalArgumentException(variant);
        }
    }

    @Test
    void summaryCountsAddUpForThisOrgOnly() throws Exception {
        Instant now = Instant.now();
        Event night = rows.night(org, now.plus(Duration.ofDays(6)));
        TicketTier tier = rows.tier(night, 1600, 50);
        ReminderRows.Org other = rows.org(now);
        Event otherNight = rows.night(other, now.plus(Duration.ofDays(6)));
        TicketTier otherTier = rows.tier(otherNight, 1600, 50);

        // Unpaid checkouts: two counted, then one of each kind that must not be.
        reservation(tier, "RELEASED", "cs_test_a_" + UUID.randomUUID(), now.minus(Duration.ofDays(1)));
        reservation(tier, "RELEASED", "cs_test_b_" + UUID.randomUUID(), now.minus(Duration.ofDays(29)));
        reservation(tier, "RELEASED", null, now.minus(Duration.ofDays(1)));
        reservation(tier, "CONFIRMED", "cs_test_c_" + UUID.randomUUID(), null);
        reservation(otherTier, "RELEASED", "cs_test_d_" + UUID.randomUUID(), now.minus(Duration.ofDays(1)));
        reservation(tier, "RELEASED", "cs_test_e_" + UUID.randomUUID(), now.minus(Duration.ofDays(31)));

        String kept = rows.email("kept");
        String refunded = rows.email("refunded");
        String waiting = rows.email("waiting");
        String hadTickets = rows.email("had");
        String soldOut = rows.email("sold");
        Instant started = now.minus(Duration.ofDays(2));
        UUID r1 = rows.waiting(org, night, tier, kept, 2, "en", false, started, started.plusSeconds(3600));
        UUID r2 = rows.waiting(org, night, tier, refunded, 1, "en", false, started, started.plusSeconds(3600));
        rows.waiting(org, night, tier, waiting, 1, "en", false, now.minusSeconds(600), now.plusSeconds(3000));
        UUID r4 = rows.waiting(org, night, tier, hadTickets, 1, "en", false, started, started.plusSeconds(3600));
        UUID r5 = rows.waiting(org, night, tier, soldOut, 1, "en", false, started, started.plusSeconds(3600));
        rows.status(r1, "SENT", null, started.plusSeconds(3600));
        rows.status(r2, "SENT", null, started.plusSeconds(3600));
        rows.status(r4, "NOT_SENT", "HAS_TICKETS", null);
        rows.status(r5, "NOT_SENT", "SOLD_OUT", null);
        Order o1 = order(night, kept, 3558L);
        Order o2 = order(night, refunded, 1779L);
        recover(r1, o1);
        recover(r2, o2);
        refund(o2, 1779L);
        // Another org's reminder, and one of ours captured 31 days ago.
        UUID foreign = rows.waiting(other, otherNight, otherTier, rows.email("foreign"), 1, "en", false, started,
                started.plusSeconds(3600));
        UUID old = rows.waiting(org, night, tier, rows.email("old"), 1, "en", false,
                now.minus(Duration.ofDays(31)), now.minus(Duration.ofDays(31)).plusSeconds(3600));
        assertThat(rows.load(foreign).getOrgId()).isEqualTo(other.id());
        assertThat(rows.load(old).getCapturedAt()).isBefore(now.minus(Duration.ofDays(30)));

        String body = mvc.perform(get(URL).with(as(user(org.org(), UserRole.MEMBER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unpaidCheckouts").value(2))
                .andExpect(jsonPath("$.captured").value(5))
                .andExpect(jsonPath("$.sent").value(2))
                .andExpect(jsonPath("$.waiting").value(1))
                .andExpect(jsonPath("$.notSent").value(2))
                .andExpect(jsonPath("$.notSentByReason.HAS_TICKETS").value(1))
                .andExpect(jsonPath("$.notSentByReason.SOLD_OUT").value(1))
                .andExpect(jsonPath("$.boughtAfter").value(1))
                .andExpect(jsonPath("$.salesMinor").value(3558))
                .andExpect(jsonPath("$.canEdit").value(false))
                .andExpect(jsonPath("$.recent.length()").value(5))
                .andExpect(jsonPath("$.recent[0].email").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        for (String address : List.of(kept, refunded, waiting, hadTickets, soldOut)) {
            assertThat(body).doesNotContain(address);
        }

        // The other org sees only its own reminder.
        mvc.perform(get(URL).with(as(other.owner())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unpaidCheckouts").value(1))
                .andExpect(jsonPath("$.captured").value(1))
                .andExpect(jsonPath("$.waiting").value(1));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"checkout-reminder-preview", "checkout-reminder-test"})
    void previewAndTestSendSpendTheirOwnBucket(String bucket) throws Exception {
        User admin = user(org.org(), UserRole.ADMIN);
        rows.tier(rows.night(org, Instant.now().plus(Duration.ofDays(4))), 1600, 50);
        flips.set(emailProps, "fromAddress", "reminders@news.imin.test");
        limiter.limit(bucket, 1);
        boolean preview = bucket.endsWith("preview");

        mvc.perform((preview ? get(URL + "/preview") : post(URL + "/test")).with(as(admin)))
                .andExpect(status().is2xxSuccessful());
        mvc.perform((preview ? get(URL + "/preview") : post(URL + "/test")).with(as(admin)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        assertThat(limiter.calls()).filteredOn(c -> c.bucket().equals(bucket))
                .extracting(RecordingRateLimiter.Call::key).containsOnly(admin.getId().toString());
        if (!preview) assertThat(testsSentTo(admin.getEmail())).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private long testsSentTo(String to) {
        return mockingDetails(provider).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendBatch"))
                .flatMap(i -> ((List<?>) i.getArgument(0)).stream())
                .map(o -> (CampaignEmailProvider.OutgoingEmail) o)
                .filter(o -> to.equals(o.to()))
                .count();
    }

    private User user(Organization o, UserRole role) {
        User u = new User();
        u.setEmail(fx.email("staff"));
        u.setOrgId(o.getId());
        u.setRole(role);
        u.setFirstName("Camille");
        return users.save(u);
    }

    private static RequestPostProcessor as(User u) {
        AuthPrincipal p = new AuthPrincipal(u.getId(), u.getOrgId(), u.getRole(), UUID.randomUUID());
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()))));
    }

    private void reservation(TicketTier t, String status, String sessionId, Instant releasedAt) {
        jdbc.update("""
                INSERT INTO ticket_reservations (id, tier_id, qty, stripe_session_id, status, created_at, expires_at,
                                                 released_at)
                VALUES (?, ?, 1, ?, ?, now(), now(), ?)
                """, UUID.randomUUID(), t.getId(), sessionId, status,
                releasedAt == null ? null : Timestamp.from(releasedAt));
    }

    private Order order(Event e, String email, long total) {
        Order o = fx.order(e, email);
        o.setTotalMinor(total);
        return orders.save(o);
    }

    private void recover(UUID reminder, Order o) {
        jdbc.update("UPDATE checkout_reminders SET recovered_order_id = ?, recovered_at = now() WHERE id = ?",
                o.getId(), reminder);
    }

    private void refund(Order o, long amount) {
        Refund r = new Refund();
        r.setOrderId(o.getId());
        r.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        r.setStripeRefundId("re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        r.setAmountMinor(amount);
        r.setCurrency("EUR");
        r.setReason(RefundReason.OTHER);
        r.setStatus(RefundStatus.SUCCEEDED);
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        refunds.save(r);
    }
}

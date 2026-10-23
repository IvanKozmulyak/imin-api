package com.imin.iminapi.checkoutreminder;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.checkoutreminder.repository.CheckoutReminderRepository;
import com.imin.iminapi.checkoutreminder.repository.OrgCheckoutReminderSettingRepository;
import com.imin.iminapi.marketing.webhook.ResendWebhookProperties;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PgFaults;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A bounce or spam report on a reminder is honoured the same way as on any other marketing email. */
@IminIntegrationTest
class CheckoutReminderDeliveryTest {

    private static final String SECRET_B64 = "cmVtaW5kZXJzZWNyZXQwMQ=="; // base64("remindersecret01")

    @Autowired MockMvc mvc;
    @Autowired ResendWebhookProperties webhookProps;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired OrganizationRepository orgs;
    @Autowired EventRepository events;
    @Autowired CheckoutReminderRepository reminders;
    @Autowired OrgCheckoutReminderSettingRepository settings;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;

    private ReminderRows rows;
    private ReminderRows.Org org;
    private String email;
    private UUID reminderId;
    private String messageId;
    private Membership member;

    @BeforeEach
    void setUp() {
        flips.set(webhookProps, "secret", "whsec_" + SECRET_B64);
        Instant now = Instant.now();
        rows = new ReminderRows(fx, orgs, events, reminders, settings, consumers, memberships, jdbc);
        org = rows.org(now);
        Event night = rows.night(org, now.plus(Duration.ofDays(5)));
        TicketTier tier = rows.tier(night, 1600, 50);
        email = rows.email("delivery");
        reminderId = rows.waiting(org, night, tier, email, 2, "en", false, now.minus(Duration.ofHours(2)),
                now.minus(Duration.ofHours(1)));
        messageId = "msg_" + UUID.randomUUID();
        jdbc.update("UPDATE checkout_reminders SET status = 'SENT', sent_at = now(), provider_message_id = ? WHERE id = ?",
                messageId, reminderId);
        member = rows.member(org, email, "subscribed");
    }

    @AfterEach
    void cleanUp() {
        rows.deleteOwn();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"permanentBounce", "complaint", "transientBounce", "delivered"})
    void bounceAndComplaintOnAReminderAreHonoured(String variant) throws Exception {
        String body = switch (variant) {
            case "permanentBounce" -> event("email.bounced", ",\"bounce\":{\"type\":\"Permanent\"}");
            case "transientBounce" -> event("email.bounced", ",\"bounce\":{\"type\":\"Transient\"}");
            case "complaint" -> event("email.complained", "");
            case "delivered" -> event("email.delivered", "");
            default -> throw new IllegalArgumentException(variant);
        };

        deliver("svix_" + UUID.randomUUID(), body).andExpect(status().isOk());

        boolean permanent = variant.equals("permanentBounce");
        boolean complaint = variant.equals("complaint");
        assertThat(count("SELECT count(*) FROM suppression_entries WHERE scope = 'deliverability' AND normalized_email = ?",
                email)).isEqualTo(permanent ? 1 : 0);
        assertThat(count("SELECT count(*) FROM marketing_optouts WHERE email_normalized = ? AND org_id = ?",
                email, org.id())).isEqualTo(complaint ? 1 : 0);
        assertThat(count("SELECT count(*) FROM suppression_entries WHERE scope = 'marketing' AND org_id = ?"
                + " AND membership_id = ?", org.id(), member.getMembershipId())).isEqualTo(complaint ? 1 : 0);
        assertThat(rows.load(reminderId).getComplainedAt() != null).isEqualTo(complaint);
        assertThat(rows.load(reminderId).getDeliveredAt() != null).isEqualTo(variant.equals("delivered"));
    }

    @Test
    void aComplaintWhoseOptOutWriteFailsIsRetriedUntilRecorded() throws Exception {
        String body = event("email.complained", "");
        String svixId = "svix_" + UUID.randomUUID();

        try (PgFaults.Fault ignored = PgFaults.failWrites(jdbc, "marketing_optouts", "org_id", org.id())) {
            deliver(svixId, body).andExpect(status().isInternalServerError());
        }
        assertThat(optOuts()).isZero();
        assertThat(rows.load(reminderId).getComplainedAt()).isNull();

        deliver(svixId, body).andExpect(status().isOk());
        assertThat(optOuts()).isEqualTo(1);
        assertThat(rows.load(reminderId).getComplainedAt()).isNotNull();
    }

    private ResultActions deliver(String svixId, String body) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        return mvc.perform(post("/api/v1/public/webhooks/resend")
                .header("svix-id", svixId).header("svix-timestamp", ts)
                .header("svix-signature", sign(svixId, ts, body))
                .contentType("application/json").content(body));
    }

    private int optOuts() {
        return count("SELECT count(*) FROM marketing_optouts WHERE email_normalized = ? AND org_id = ?", email, org.id());
    }

    private String event(String type, String extra) {
        return "{\"type\":\"" + type + "\",\"created_at\":\"2026-11-05T12:00:00Z\",\"data\":{\"email_id\":\""
                + messageId + "\",\"to\":[\"" + email + "\"]" + extra + "}}";
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private String sign(String id, String ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(SECRET_B64), "HmacSHA256"));
        return "v1," + Base64.getEncoder().encodeToString(
                mac.doFinal((id + "." + ts + "." + body).getBytes(StandardCharsets.UTF_8)));
    }
}

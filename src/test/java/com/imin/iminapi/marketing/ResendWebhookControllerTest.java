package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.webhook.ResendWebhookProperties;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The signed Resend webhook end to end: signature, dedup claim and the real projector. */
@IminIntegrationTest
class ResendWebhookControllerTest {

    private static final String SECRET_B64 = "c3VwZXJzZWNyZXRrZXkw"; // base64("supersecretkey0")

    @Autowired MockMvc mvc;
    @Autowired CampaignRecipientRepository recipientRepo;
    @Autowired CampaignRepository campaignRepo;
    @Autowired ResendWebhookProperties webhookProps;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();

    @BeforeEach
    void signingSecret() {
        flips.set(webhookProps, "secret", "whsec_" + SECRET_B64);
    }

    /** The campaigns are left `sending`, which the dispatcher reclaims for every org once stale. */
    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    /** campaign_recipients.campaign_id is a NOT NULL FK, so each recipient gets a real parent campaign. */
    private CampaignRecipient recipient(String email) {
        UUID orgId = UUID.randomUUID();
        orgIds.add(orgId);
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("webhook-ctrl-test");
        c.setStatus("sending");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaignRepo.save(c);

        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setEmail(email);
        r.setStatus("sent");
        r.setProviderMessageId("msg_" + UUID.randomUUID());
        return recipientRepo.save(r);
    }

    private MockHttpServletRequestBuilder signed(String svixId, String body) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        return post("/api/v1/public/webhooks/resend")
                .header("svix-id", svixId).header("svix-timestamp", ts)
                .header("svix-signature", sign(svixId, ts, body))
                .contentType("application/json").content(body);
    }

    private String sign(String id, String ts, String body) throws Exception {
        byte[] key = Base64.getDecoder().decode(SECRET_B64);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return "v1," + Base64.getEncoder().encodeToString(
            mac.doFinal((id + "." + ts + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private CampaignRecipient reload(CampaignRecipient r) {
        return recipientRepo.findById(r.getId()).orElseThrow();
    }

    @Test
    void validDeliveredWebhookMarksTheRecipientDelivered() throws Exception {
        String email = fx.email("delivered");
        CampaignRecipient r = recipient(email);
        String body = "{\"type\":\"email.delivered\",\"data\":{\"email_id\":\"" + r.getProviderMessageId()
                + "\",\"to\":[\"" + email + "\"]},\"created_at\":\"2026-07-11T00:00:00Z\"}";

        mvc.perform(signed("svix_" + UUID.randomUUID(), body)).andExpect(status().isOk());

        CampaignRecipient after = reload(r);
        assertThat(after.getStatus()).isEqualTo("delivered");
        assertThat(after.getDeliveredAt()).isEqualTo(Instant.parse("2026-07-11T00:00:00Z"));
        assertThat(after.getLastEventAt()).isEqualTo(Instant.parse("2026-07-11T00:00:00Z"));
    }

    /**
     * The handler must pass {@code data.bounce.type} on: an absent type reads as transient, so only a
     * Permanent bounce reaches the projector as hard_bounce; the suppression is ResendWebhookProjectorTest's.
     */
    @Test
    void aPermanentBounceReachesTheProjectorAsAHardBounce() throws Exception {
        String email = fx.email("bounce");
        CampaignRecipient r = recipient(email);
        String body = "{\"type\":\"email.bounced\",\"data\":{\"email_id\":\"" + r.getProviderMessageId() + "\","
                + "\"to\":[\"" + email + "\"],"
                + "\"bounce\":{\"type\":\"Permanent\",\"subType\":\"General\",\"message\":\"no such user\"}}}";

        mvc.perform(signed("svix_" + UUID.randomUUID(), body)).andExpect(status().isOk());

        assertThat(reload(r).getErrorCode()).isEqualTo("hard_bounce");
    }

    @Test
    void badSignatureIs401AndLeavesTheRecipientUnchanged() throws Exception {
        String email = fx.email("forged");
        CampaignRecipient r = recipient(email);
        String body = "{\"type\":\"email.delivered\",\"data\":{\"email_id\":\"" + r.getProviderMessageId()
                + "\",\"to\":[\"" + email + "\"]}}";

        mvc.perform(post("/api/v1/public/webhooks/resend")
                .header("svix-id", "svix_" + UUID.randomUUID())
                .header("svix-timestamp", String.valueOf(System.currentTimeMillis() / 1000L))
                .header("svix-signature", "v1,deadbeef")
                .contentType("application/json").content(body))
           .andExpect(status().isUnauthorized());

        CampaignRecipient after = reload(r);
        assertThat(after.getStatus()).isEqualTo("sent");
        assertThat(after.getDeliveredAt()).isNull();
    }

    @Test
    void aReplayedSvixIdIsAckedWithoutProjectingAgain() throws Exception {
        String email = fx.email("replay");
        CampaignRecipient r = recipient(email);
        Instant first = Instant.parse("2026-07-11T10:00:00Z");
        String svixId = "svix_dup_" + UUID.randomUUID();
        String body = "{\"type\":\"email.opened\",\"data\":{\"email_id\":\"" + r.getProviderMessageId()
                + "\",\"to\":[\"" + email + "\"]},\"created_at\":\"%s\"}";

        mvc.perform(signed(svixId, body.formatted(first))).andExpect(status().isOk());
        // Same delivery id, re-signed, with a later timestamp: a second projection would move opened_at.
        mvc.perform(signed(svixId, body.formatted(first.plus(1, ChronoUnit.HOURS)))).andExpect(status().isOk());

        assertThat(reload(r).getOpenedAt()).isEqualTo(first);
    }
}

package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.webhook.ResendWebhookProjector;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
@TestPropertySource(properties = "imin.marketing.resend-webhook.secret=whsec_c3VwZXJzZWNyZXRrZXkw") // base64("supersecretkey0")
class ResendWebhookControllerTest {

    @Autowired MockMvc mvc;
    @Autowired CampaignRecipientRepository recipientRepo;
    @Autowired CampaignRepository campaignRepo;
    @MockitoBean ResendWebhookProjector projector;

    private static final String SECRET_B64 = "c3VwZXJzZWNyZXRrZXkw";

    /**
     * campaign_recipients.campaign_id has a NOT NULL FK to campaigns(id) (V53:7),
     * enforced under H2 MODE=PostgreSQL, so a recipient can't be saved without a
     * real parent campaign. Seed one (mirrors the working ResendWebhookProjectorTest
     * fixture) and return its id for the recipient's campaign_id.
     */
    private UUID seedCampaign() {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(UUID.randomUUID());
        c.setChannel("email");
        c.setName("webhook-ctrl-test");
        c.setStatus("sending");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaignRepo.save(c);
        return c.getId();
    }

    private String sign(String id, String ts, String body) throws Exception {
        byte[] key = Base64.getDecoder().decode(SECRET_B64);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return "v1," + Base64.getEncoder().encodeToString(
            mac.doFinal((id + "." + ts + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void validDeliveredWebhookProjectsAnd200() throws Exception {
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(seedCampaign());
        r.setEmail("x@example.com");
        r.setStatus("sent");
        r.setProviderMessageId("msg_live_1");
        recipientRepo.save(r);

        String body = "{\"type\":\"email.delivered\",\"data\":{\"email_id\":\"msg_live_1\",\"to\":[\"x@example.com\"]},\"created_at\":\"2026-07-11T00:00:00Z\"}";
        String id = "svix_" + UUID.randomUUID();
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);

        mvc.perform(post("/api/v1/public/webhooks/resend")
                .header("svix-id", id).header("svix-timestamp", ts)
                .header("svix-signature", sign(id, ts, body))
                .contentType("application/json").content(body))
           .andExpect(status().isOk());

        // signature: project(campaignId, recipientId, membershipId, email, type, occurredAt)
        Mockito.verify(projector).project(any(), eq(r.getId()), any(),
            eq("x@example.com"), eq("email.delivered"), any());
    }

    @Test
    void badSignatureIs401AndNoProjection() throws Exception {
        String body = "{\"type\":\"email.delivered\",\"data\":{\"email_id\":\"msg_live_1\"}}";
        mvc.perform(post("/api/v1/public/webhooks/resend")
                .header("svix-id", "svix_x").header("svix-timestamp",
                     String.valueOf(System.currentTimeMillis() / 1000L))
                .header("svix-signature", "v1,deadbeef")
                .contentType("application/json").content(body))
           .andExpect(status().isUnauthorized());
        Mockito.verifyNoInteractions(projector);
    }

    @Test
    void duplicateEventIsAckedWithoutSecondProjection() throws Exception {
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(seedCampaign());
        r.setEmail("d@example.com");
        r.setStatus("sent");
        r.setProviderMessageId("msg_dup_1");
        recipientRepo.save(r);

        String body = "{\"type\":\"email.opened\",\"data\":{\"email_id\":\"msg_dup_1\",\"to\":[\"d@example.com\"]}}";
        String id = "svix_dup_" + UUID.randomUUID();
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        var req = post("/api/v1/public/webhooks/resend")
                .header("svix-id", id).header("svix-timestamp", ts)
                .header("svix-signature", sign(id, ts, body))
                .contentType("application/json").content(body);

        mvc.perform(req).andExpect(status().isOk());
        mvc.perform(req).andExpect(status().isOk()); // replay
        Mockito.verify(projector, Mockito.times(1)).project(any(), any(), any(), any(), any(), any());
    }
}

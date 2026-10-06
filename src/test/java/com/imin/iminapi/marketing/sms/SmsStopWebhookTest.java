package com.imin.iminapi.marketing.sms;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof that a signed inbound STOP suppresses the number: seed a member
 * with explicit SMS consent, POST a signed STOP to the public webhook, and assert
 * the membership flips to sms_consent_status=unsubscribed. Also asserts a forged
 * signature is rejected (401) and leaves consent intact.
 */
@IminIntegrationTest
class SmsStopWebhookTest {

    private static final String SECRET = "sms-test-secret-0123456789";
    @Autowired MockMvc mvc;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired SmsProperties smsProperties;
    @Autowired PropertyFlips flips;

    // A STOP applies to every member with the number, and the database is shared: one number per test.
    private final String phone = "+336" + (10_000_000 + ThreadLocalRandom.current().nextInt(90_000_000));

    @BeforeEach
    void webhookSecret() {
        flips.set(smsProperties, "webhookSecret", SECRET);
    }

    private Membership seedSubscribedMember() {
        Consumer c = new Consumer();
        c.setNormalizedEmail("stop-" + UUID.randomUUID() + "@example.com");
        c.setDisplayName("Attendee");
        c = consumers.save(c);

        Membership m = new Membership();
        m.setOrgId(UUID.randomUUID());
        m.setConsumerId(c.getConsumerId());
        m.setDisplayName("Attendee");
        m.setPhoneE164(phone);
        m.setSmsConsentStatus("subscribed");
        m.setSmsConsentBasis("explicit");
        return memberships.save(m);
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void signedStopUnsubscribesTheNumber() throws Exception {
        Membership m = seedSubscribedMember();
        String body = "{\"id\":\"mo-" + UUID.randomUUID() + "\",\"type\":\"mo\",\"originator\":\"" + phone + "\",\"body\":\"STOP\"}";

        mvc.perform(post("/api/v1/public/webhooks/sms")
                        .header("Messagebird-Signature", sign(body))
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());

        Membership after = memberships.findByIdAndOrgId(m.getMembershipId(), m.getOrgId()).orElseThrow();
        assertThat(after.getSmsConsentStatus()).isEqualTo("unsubscribed");
        assertThat(after.getSmsConsentBasis()).isNull();
    }

    @Test
    void forgedSignatureIs401AndConsentUntouched() throws Exception {
        Membership m = seedSubscribedMember();
        String body = "{\"id\":\"mo-" + UUID.randomUUID() + "\",\"type\":\"mo\",\"originator\":\"" + phone + "\",\"body\":\"STOP\"}";

        mvc.perform(post("/api/v1/public/webhooks/sms")
                        .header("Messagebird-Signature", "not-a-valid-signature")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());

        Membership after = memberships.findByIdAndOrgId(m.getMembershipId(), m.getOrgId()).orElseThrow();
        assertThat(after.getSmsConsentStatus()).isEqualTo("subscribed");
    }
}

package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.webhook.ResendWebhookProjector;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class ResendWebhookProjectorTest {

    @Autowired ResendWebhookProjector projector;
    @Autowired CampaignRecipientRepository recipientRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired SuppressionRepository suppressionRepo;
    @Autowired com.imin.iminapi.marketing.repository.CampaignRepository campaignRepo;
    @MockitoBean AuditLogger auditLogger;

    private record Fixture(UUID orgId, UUID campaignId, UUID membershipId, UUID recipientId, String email) {}

    private Fixture seed(String email) {
        UUID orgId = UUID.randomUUID();

        // memberships.consumer_id is UUID NOT NULL REFERENCES consumers(consumer_id)
        // (V48__audience_memberships.sql:8); H2 MODE=PostgreSQL enforces the FK on
        // INSERT, so seed a real Consumer first (normalized_email is NOT NULL + UNIQUE
        // — V47__audience_consumers.sql:5, Consumer.java:28).
        // Consumer/Membership use @GeneratedValue — do NOT set the id; let save()
        // assign it and read it back (a non-null id makes save() a merge → optimistic-lock
        // failure on an absent row). Mirrors the working RecipientMaterializerTest fixture.
        Consumer consumer = new Consumer();
        consumer.setNormalizedEmail("seed-" + UUID.randomUUID() + "@example.com");
        consumer = consumerRepo.save(consumer);

        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumer.getConsumerId());   // real FK target
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        m = membershipRepo.save(m);

        // The projector derives orgId from the campaign (recipient rows carry
        // no org_id — spec §2.2 V53), so the campaign must exist with this org.
        com.imin.iminapi.marketing.model.Campaign c = new com.imin.iminapi.marketing.model.Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("proj-test");
        c.setStatus("sending");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaignRepo.save(c);

        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(m.getMembershipId());
        r.setEmail(email);
        r.setStatus("sent");
        r.setProviderMessageId("msg_" + UUID.randomUUID());
        recipientRepo.save(r);
        return new Fixture(orgId, c.getId(), m.getMembershipId(), r.getId(), email);
    }

    @Test
    void deliveredMarksRecipientDelivered() {
        Fixture f = seed("a@example.com");
        projector.project(f.campaignId(), f.recipientId(), f.membershipId(),
            "a@example.com", "email.delivered", Instant.now());
        assertThat(recipientRepo.findById(f.recipientId()).orElseThrow().getStatus())
            .isEqualTo("delivered");
    }

    @Test
    void bouncedSuppressesDeliverabilityByNormalizedEmail() {
        Fixture f = seed("Bounce@Example.com");
        projector.project(f.campaignId(), f.recipientId(), f.membershipId(),
            "Bounce@Example.com", "email.bounced", Instant.now());
        assertThat(recipientRepo.findById(f.recipientId()).orElseThrow().getStatus())
            .isEqualTo("bounced");
        // normalized lower+trim per EmailNormalizer
        assertThat(suppressionRepo.findDeliverabilityByEmail("bounce@example.com")).isPresent();
    }

    @Test
    void complainedSuppressesMarketingAndMarksComplained() {
        Fixture f = seed("spam@example.com");
        projector.project(f.campaignId(), f.recipientId(), f.membershipId(),
            "spam@example.com", "email.complained", Instant.now());
        assertThat(recipientRepo.findById(f.recipientId()).orElseThrow().getStatus())
            .isEqualTo("complained");
        assertThat(suppressionRepo.findMarketingByOrgAndMembership(f.orgId(), f.membershipId()))
            .isPresent();
    }

    @Test
    void openedStampsRecipientAndMembership() {
        Fixture f = seed("open@example.com");
        Instant when = Instant.now();
        projector.project(f.campaignId(), f.recipientId(), f.membershipId(),
            "open@example.com", "email.opened", when);
        assertThat(recipientRepo.findById(f.recipientId()).orElseThrow().getOpenedAt()).isNotNull();
        assertThat(membershipRepo.findByIdAndOrgId(f.membershipId(), f.orgId())
            .orElseThrow().getLastEmailOpen()).isNotNull();
    }

    @Test
    void clickedStampsRecipientAndMembership() {
        Fixture f = seed("click@example.com");
        projector.project(f.campaignId(), f.recipientId(), f.membershipId(),
            "click@example.com", "email.clicked", Instant.now());
        assertThat(recipientRepo.findById(f.recipientId()).orElseThrow().getClickedAt()).isNotNull();
        assertThat(membershipRepo.findByIdAndOrgId(f.membershipId(), f.orgId())
            .orElseThrow().getLastEmailClick()).isNotNull();
    }
}

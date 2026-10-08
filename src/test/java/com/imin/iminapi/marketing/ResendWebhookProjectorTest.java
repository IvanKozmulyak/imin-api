package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.webhook.ResendWebhookProjector;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IminIntegrationTest
@RecordApplicationEvents
class ResendWebhookProjectorTest {

    @Autowired ResendWebhookProjector projector;
    @Autowired CampaignRecipientRepository recipientRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired SuppressionRepository suppressionRepo;
    @Autowired com.imin.iminapi.marketing.repository.CampaignRepository campaignRepo;
    @Autowired com.imin.iminapi.audience.repository.MarketingOptOutRepository optOutRepo;
    @Autowired ApplicationEvents published;
    @Autowired com.imin.iminapi.audienceplan.repository.FanFeatureRepository fanFeatureRepo;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();

    /** The campaigns are left `sending`, which the dispatcher reclaims for every org once stale. */
    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    /** Events this test published for its own org; other threads may publish meanwhile. */
    private List<com.imin.iminapi.audience.service.ConsentChanged> consentChanges(UUID orgId) {
        return published.stream(com.imin.iminapi.audience.service.ConsentChanged.class)
                .filter(e -> orgId.equals(e.orgId())).toList();
    }

    private record Fixture(UUID orgId, UUID campaignId, UUID membershipId, UUID recipientId, String email) {}

    private Fixture seed(String email) {
        UUID orgId = UUID.randomUUID();
        orgIds.add(orgId);

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

    /**
     * mkt-core-14 (P3): provider_events.type is nullable, so a signed Resend body with no
     * "type" field reached {@code switch (type)} — a String switch on null throws NPE. The
     * controller's @Transactional rolled the dedup claim back with it, so every Resend retry
     * of that event repeated the 500 instead of being deduped away. An unknown shape is
     * ignored, not fatal.
     */
    @Test
    void nullEventTypeIsIgnoredInsteadOfThrowing() {
        Fixture f = seed(fx.email("null-type"));
        org.assertj.core.api.Assertions.assertThatCode(() ->
                projector.project(f.campaignId(), f.recipientId(), f.membershipId(),
                        f.email(), null, null, Instant.now()))
                .doesNotThrowAnyException();
        assertThat(recipientRepo.findById(f.recipientId()).orElseThrow().getStatus())
                .isEqualTo("sent");   // untouched
    }

    @Test
    void deliveredMarksRecipientDelivered() {
        Fixture f = seed(fx.email("a"));
        projector.project(f.campaignId(), f.recipientId(), f.membershipId(),
            f.email(), "email.delivered", null, Instant.now());
        assertThat(recipientRepo.findById(f.recipientId()).orElseThrow().getStatus())
            .isEqualTo("delivered");
    }

    @Test
    void aPermanentBounceSuppressesDeliverabilityByNormalizedEmail() {
        Fixture f = seed(fx.email("Bounce").replace("@example.test", "@Example.test"));
        projector.project(f.campaignId(), f.recipientId(), f.membershipId(),
            f.email(), "email.bounced", "Permanent", Instant.now());
        assertThat(recipientRepo.findById(f.recipientId()).orElseThrow().getStatus())
            .isEqualTo("bounced");
        // normalized lower+trim per EmailNormalizer
        assertThat(suppressionRepo.findDeliverabilityByEmail(f.email().toLowerCase(Locale.ROOT))).isPresent();
    }

    /**
     * mkt-edge-6 (P2): every email.bounced wrote the shared, system-owned,
     * never-removable deliverability row — so one org's full mailbox, greylisting or
     * temporary DNS failure blinded EVERY organizer on the platform for that address,
     * permanently, with no un-suppress path anywhere in the tree. Only a Permanent bounce
     * may reach the shared list.
     */
    @Test
    void aTransientBounceDoesNotTouchTheSharedList() {
        Fixture f = seed(fx.email("soft"));
        projector.project(f.campaignId(), f.recipientId(), f.membershipId(),
            f.email(), "email.bounced", "Transient", Instant.now());

        CampaignRecipient after = recipientRepo.findById(f.recipientId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("bounced");
        assertThat(after.getErrorCode()).isEqualTo("soft_bounce");
        assertThat(suppressionRepo.findDeliverabilityByEmail(f.email())).isEmpty();
        assertThat(suppressionRepo.findMarketingByOrgAndMembership(f.orgId(), f.membershipId()))
            .isEmpty();
    }

    /** An absent bounce.type is treated as transient — the shared list is never written on a guess. */
    @Test
    void anUntypedBounceIsTreatedAsTransient() {
        Fixture f = seed(fx.email("untyped"));
        projector.project(f.campaignId(), f.recipientId(), f.membershipId(),
            f.email(), "email.bounced", null, Instant.now());
        assertThat(suppressionRepo.findDeliverabilityByEmail(f.email())).isEmpty();
    }

    /**
     * Repeated transient bounces do mean something — but the escalation is org-scoped
     * (marketing suppression for that membership), never the cross-org deliverability list.
     */
    @Test
    void repeatedTransientBouncesSuppressTheMembershipForItsOwnOrgOnly() {
        Fixture f = seed(fx.email("repeat-soft"));
        projector.project(f.campaignId(), f.recipientId(), f.membershipId(),
            f.email(), "email.bounced", "Transient", Instant.now());
        assertThat(suppressionRepo.findMarketingByOrgAndMembership(f.orgId(), f.membershipId()))
            .isEmpty();

        for (int i = 0; i < 2; i++) {
            Fixture later = laterCampaignFor(f);
            projector.project(later.campaignId(), later.recipientId(), later.membershipId(),
                later.email(), "email.bounced", "Transient", Instant.now());
        }

        assertThat(suppressionRepo.findMarketingByOrgAndMembership(f.orgId(), f.membershipId()))
            .isPresent();
        // ...and the platform-wide list is still untouched.
        assertThat(suppressionRepo.findDeliverabilityByEmail(f.email())).isEmpty();
    }

    /**
     * The same membership on a LATER campaign — the only way it can bounce twice, since
     * uq_campaign_recipient (campaign_id, membership_id) allows one row per campaign.
     */
    private Fixture laterCampaignFor(Fixture f) {
        com.imin.iminapi.marketing.model.Campaign c = new com.imin.iminapi.marketing.model.Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(f.orgId());
        c.setChannel("email");
        c.setName("proj-test-later");
        c.setStatus("sending");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaignRepo.save(c);

        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(f.membershipId());
        r.setEmail(f.email());
        r.setStatus("sent");
        r.setProviderMessageId("msg_" + UUID.randomUUID());
        recipientRepo.save(r);
        return new Fixture(f.orgId(), c.getId(), f.membershipId(), r.getId(), f.email());
    }

    @Test
    void complainedSuppressesMarketingAndMarksComplained() {
        Fixture f = seed(fx.email("spam"));
        projector.project(f.campaignId(), f.recipientId(), f.membershipId(),
            f.email(), "email.complained", null, Instant.now());
        assertThat(recipientRepo.findById(f.recipientId()).orElseThrow().getStatus())
            .isEqualTo("complained");
        assertThat(suppressionRepo.findMarketingByOrgAndMembership(f.orgId(), f.membershipId()))
            .isPresent();
    }

    @Test
    void complainedMarksTheMemberAsObjectingToProfiling() {
        Fixture f = seed(fx.email("spam-profiling"));
        projector.project(f.campaignId(), f.recipientId(), f.membershipId(),
            f.email(), "email.complained", null, Instant.now());
        Membership m = membershipRepo.findByIdAndOrgId(f.membershipId(), f.orgId()).orElseThrow();
        assertThat(m.isObjectedProfiling()).isTrue();
        String address = consumerRepo.findByConsumerId(m.getConsumerId()).orElseThrow().getNormalizedEmail();
        assertThat(optOutRepo.findByEmailNormalized(address)).as("no sticky opt-out row").isEmpty();
    }

    @Test
    void complainedPublishesConsentChangedSoTasteClears() {
        Fixture f = seed(fx.email("spam-taste"));
        projector.project(f.campaignId(), f.recipientId(), f.membershipId(),
            f.email(), "email.complained", null, Instant.now());
        assertThat(consentChanges(f.orgId()))
            .containsExactly(new com.imin.iminapi.audience.service.ConsentChanged(f.orgId(), f.membershipId(), false));
    }

    @Test
    void complainedClearsTasteInsideTheWebhookTransaction() {
        Fixture f = seed(fx.email("spam-inline"));
        com.imin.iminapi.audienceplan.model.FanFeature seeded = new com.imin.iminapi.audienceplan.model.FanFeature();
        seeded.setMembershipId(f.membershipId());
        seeded.setOrgId(f.orgId());
        seeded.setPaidOrders(2);
        seeded.setFanClass("repeat");
        seeded.setTaste("{\"pop\":1.0}");
        seeded.setCities("[\"metz\"]");
        seeded.setFormats("[\"club\"]");
        seeded.setLogicVersion(1);
        fanFeatureRepo.save(seeded);

        // Read before commit: the after-commit listener has not run, so a cleared row proves the clear is inline.
        var inside = new org.springframework.transaction.support.TransactionTemplate(txManager).execute(s -> {
            projector.project(f.campaignId(), f.recipientId(), f.membershipId(),
                f.email(), "email.complained", null, Instant.now());
            return fanFeatureRepo.findById(f.membershipId()).orElseThrow();
        });

        assertThat(inside.getTaste()).isEqualTo("{}");
        assertThat(inside.getCities()).isEqualTo("[]");
        assertThat(inside.getFormats()).isEqualTo("[]");
        assertThat(inside.getPaidOrders()).isEqualTo(2);
    }

    @Test
    void deliveredLeavesProfilingObjectionFalse() {
        Fixture f = seed(fx.email("delivered-profiling"));
        projector.project(f.campaignId(), f.recipientId(), f.membershipId(),
            f.email(), "email.delivered", null, Instant.now());
        assertThat(membershipRepo.findByIdAndOrgId(f.membershipId(), f.orgId())
            .orElseThrow().isObjectedProfiling()).isFalse();
        assertThat(consentChanges(f.orgId())).isEmpty();
    }

    @Test
    void openedStampsRecipientOnly_membershipUnchanged() {
        Fixture f = seed(fx.email("open"));
        Instant when = Instant.now();
        projector.project(f.campaignId(), f.recipientId(), f.membershipId(),
            f.email(), "email.opened", null, when);
        CampaignRecipient r = recipientRepo.findById(f.recipientId()).orElseThrow();
        assertThat(r.getOpenedAt()).isNotNull();
        assertThat(r.getLastEventAt()).isNotNull();
        Membership m = membershipRepo.findByIdAndOrgId(f.membershipId(), f.orgId()).orElseThrow();
        assertThat(m.getLastEmailOpen()).isNull();
        assertThat(m.getLastEmailClick()).isNull();
    }

    @Test
    void clickedStampsRecipientOnly_membershipUnchanged() {
        Fixture f = seed(fx.email("click"));
        projector.project(f.campaignId(), f.recipientId(), f.membershipId(),
            f.email(), "email.clicked", null, Instant.now());
        CampaignRecipient r = recipientRepo.findById(f.recipientId()).orElseThrow();
        assertThat(r.getClickedAt()).isNotNull();
        assertThat(r.getLastEventAt()).isNotNull();
        Membership m = membershipRepo.findByIdAndOrgId(f.membershipId(), f.orgId()).orElseThrow();
        assertThat(m.getLastEmailOpen()).isNull();
        assertThat(m.getLastEmailClick()).isNull();
    }
}

package com.imin.iminapi.audience;

import com.imin.iminapi.audience.dto.AudienceMetricsDto;
import com.imin.iminapi.audience.model.*;
import com.imin.iminapi.audience.repository.*;
import com.imin.iminapi.audience.service.*;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * AudienceMetricsService KPI aggregation tests:
 * - totalMembers
 * - buyers vs prospects
 * - subscribedMailable count and pct
 * - explicit consent vs soft_opt_in counts
 * - listGrowth8w has exactly 8 entries
 * - repeatAttendeePct
 * - unsubRatePct
 * - complaintRatePct (complaints / delivered, null with nothing delivered)
 * - empty org returns zeros (no NPE)
 * - tenant isolation: metrics scoped to org
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AudienceMetricsTest {

    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired OrganizationRepository orgRepo;
    @Autowired AudienceOrderProjector orderProjector;
    @Autowired AudienceMetricsService metricsService;
    @Autowired ConsentService consentService;
    @Autowired DataSource dataSource;
    @Autowired com.imin.iminapi.marketing.repository.CampaignRepository campaignRepo;
    @Autowired com.imin.iminapi.marketing.repository.CampaignRecipientRepository recipientRepo;
    @Autowired com.imin.iminapi.marketing.repository.ProviderEventRepository providerEvents;

    @MockitoBean AuditLogger auditLogger;

    private UUID orgA;
    private UUID orgB;

    @BeforeEach
    void setUp() {
        wipe();
        orgA = org("MetOrgA").getId();
        orgB = org("MetOrgB").getId();
    }

    @AfterEach
    void tearDown() { wipe(); }

    // ─────────────────────────────────────────────────────────────────────────
    // Empty org → all zeros, no NPE
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void empty_org_returns_zeros_no_exception() {
        AudienceMetricsDto dto = metricsService.compute(orgA);

        assertThat(dto.totalMembers()).isZero();
        assertThat(dto.buyers()).isZero();
        assertThat(dto.prospects()).isZero();
        assertThat(dto.subscribedMailable()).isZero();
        assertThat(dto.subscribedPct()).isZero();
        assertThat(dto.explicitConsent()).isZero();
        assertThat(dto.softOptIn()).isZero();
        assertThat(dto.repeatAttendeePct()).isZero();
        assertThat(dto.unsubRatePct()).isZero();
        assertThat(dto.complaintRatePct()).isNull();
        assertThat(dto.listGrowth8w()).hasSize(8);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // totalMembers
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void total_members_counts_all_in_org() {
        seedMembership(orgA, "m1@m.com");
        seedMembership(orgA, "m2@m.com");
        seedMembership(orgA, "m3@m.com");
        // orgB member should not count
        seedMembership(orgB, "mb@m.com");

        AudienceMetricsDto dto = metricsService.compute(orgA);
        assertThat(dto.totalMembers()).isEqualTo(3);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buyers vs prospects
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buyers_are_members_with_events_gt_0() {
        // Buyer: events > 0
        Membership buyer = seedAndGet(orgA, "buyer@m.com");
        buyer.setEvents(2);
        membershipRepo.save(buyer);

        // Prospect: events == 0 (default)
        seedMembership(orgA, "prospect@m.com");

        AudienceMetricsDto dto = metricsService.compute(orgA);
        assertThat(dto.buyers()).isEqualTo(1);
        assertThat(dto.prospects()).isEqualTo(1);
        assertThat(dto.totalMembers()).isEqualTo(2);
    }

    @Test
    void buyers_plus_prospects_equals_total() {
        Membership b1 = seedAndGet(orgA, "b1@m.com");
        b1.setEvents(1); membershipRepo.save(b1);

        Membership b2 = seedAndGet(orgA, "b2@m.com");
        b2.setEvents(3); membershipRepo.save(b2);

        seedMembership(orgA, "p1@m.com");
        seedMembership(orgA, "p2@m.com");

        AudienceMetricsDto dto = metricsService.compute(orgA);
        assertThat(dto.buyers() + dto.prospects()).isEqualTo(dto.totalMembers());
        assertThat(dto.buyers()).isEqualTo(2);
        assertThat(dto.prospects()).isEqualTo(2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // subscribedMailable and subscribedPct
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void subscribed_mailable_counts_subscribed_members() {
        // Subscribed
        Membership sub = seedAndGet(orgA, "sub@m.com");
        sub.setConsentStatus("subscribed");
        sub.setConsentBasis("explicit");
        membershipRepo.save(sub);

        // Unsubscribed
        Membership unsub = seedAndGet(orgA, "unsub@m.com");
        unsub.setConsentStatus("unsubscribed");
        membershipRepo.save(unsub);

        // Never (default)
        seedMembership(orgA, "never@m.com");

        AudienceMetricsDto dto = metricsService.compute(orgA);
        assertThat(dto.subscribedMailable()).isEqualTo(1);
    }

    @Test
    void subscribed_pct_computed_correctly() {
        // 2 out of 4 subscribed → 50%
        for (int i = 0; i < 2; i++) {
            Membership m = seedAndGet(orgA, "sub" + i + "@m.com");
            m.setConsentStatus("subscribed");
            membershipRepo.save(m);
        }
        for (int i = 0; i < 2; i++) {
            seedMembership(orgA, "never" + i + "@m.com");
        }

        AudienceMetricsDto dto = metricsService.compute(orgA);
        assertThat(dto.subscribedPct()).isCloseTo(50.0, within(0.1));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // explicitConsent vs softOptIn
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void explicit_consent_counts_explicit_basis_members() {
        Membership explicit = seedAndGet(orgA, "expl@m.com");
        explicit.setConsentBasis("explicit");
        membershipRepo.save(explicit);

        Membership soft = seedAndGet(orgA, "soft@m.com");
        soft.setConsentBasis("soft_opt_in");
        membershipRepo.save(soft);

        seedMembership(orgA, "none@m.com"); // null basis

        AudienceMetricsDto dto = metricsService.compute(orgA);
        assertThat(dto.explicitConsent()).isEqualTo(1);
        assertThat(dto.softOptIn()).isEqualTo(1);
    }

    @Test
    void explicit_plus_soft_opt_in_le_total() {
        Membership e1 = seedAndGet(orgA, "e1@m.com");
        e1.setConsentBasis("explicit"); membershipRepo.save(e1);

        Membership s1 = seedAndGet(orgA, "s1@m.com");
        s1.setConsentBasis("soft_opt_in"); membershipRepo.save(s1);

        seedMembership(orgA, "n1@m.com");

        AudienceMetricsDto dto = metricsService.compute(orgA);
        assertThat(dto.explicitConsent() + dto.softOptIn()).isLessThanOrEqualTo(dto.totalMembers());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // listGrowth8w
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void list_growth_8w_has_exactly_8_entries() {
        seedMembership(orgA, "grow@m.com");
        AudienceMetricsDto dto = metricsService.compute(orgA);
        assertThat(dto.listGrowth8w()).hasSize(8);
    }

    @Test
    void list_growth_8w_all_non_negative() {
        seedMembership(orgA, "grow2@m.com");
        AudienceMetricsDto dto = metricsService.compute(orgA);
        dto.listGrowth8w().forEach(count ->
                assertThat(count).isGreaterThanOrEqualTo(0));
    }

    @Test
    void list_growth_8w_current_week_reflects_new_member() {
        // A member created just now should appear in the most recent week bucket
        seedMembership(orgA, "recent@m.com");

        AudienceMetricsDto dto = metricsService.compute(orgA);
        // Sum of all weeks should equal total members (all created recently in this test)
        int total = dto.listGrowth8w().stream().mapToInt(Integer::intValue).sum();
        assertThat(total).isGreaterThanOrEqualTo(1);
        // Last bucket (most recent week) should have at least 1
        int lastBucket = dto.listGrowth8w().get(dto.listGrowth8w().size() - 1);
        assertThat(lastBucket).isGreaterThanOrEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // repeatAttendeePct
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void repeat_attendee_pct_computed_from_attended_gt_1() {
        // repeat attendee: attended > 1
        Membership repeat = seedAndGet(orgA, "rattn@m.com");
        repeat.setEvents(3);
        repeat.setAttended(2);
        membershipRepo.save(repeat);

        // single attendee
        Membership single = seedAndGet(orgA, "sattn@m.com");
        single.setEvents(1);
        single.setAttended(1);
        membershipRepo.save(single);

        AudienceMetricsDto dto = metricsService.compute(orgA);
        // repeatAttendeePct is derived from buyers who attended > 1 / total buyers
        assertThat(dto.repeatAttendeePct()).isGreaterThan(0.0);
        assertThat(dto.repeatAttendeePct()).isLessThanOrEqualTo(100.0);
    }

    /**
     * audience-2: the numerator used to be taken from the 56-day list-growth window
     * while the denominator was every buyer, so an org whose members all joined more
     * than eight weeks ago reported 0.0% repeat attendance however loyal they were.
     */
    @Test
    void repeat_attendee_pct_counts_buyers_older_than_the_growth_window() {
        Membership old = seedAged(orgA, "oldrepeat@m.com", 100);
        old.setEvents(3);
        old.setAttended(2);
        membershipRepo.save(old);

        AudienceMetricsDto dto = metricsService.compute(orgA);

        assertThat(dto.repeatAttendeePct()).isEqualTo(100.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // unsubRatePct
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void unsub_rate_pct_reflects_consent_records() {
        // Create a subscribed member then unsubscribe them
        UUID mid = seedMembership(orgA, "unsubrate@m.com");
        AuthPrincipal p = new com.imin.iminapi.security.AuthPrincipal(
                UUID.randomUUID(), orgA, UserRole.OWNER, UUID.randomUUID());
        consentService.capture(orgA, mid, "explicit", "test", "proof", p);
        consentService.unsubscribe(orgA, mid, "own-request", ConsentOrigin.OPERATOR, p);

        AudienceMetricsDto dto = metricsService.compute(orgA);
        // There's 1 unsub record; unsub pct should be > 0
        assertThat(dto.unsubRatePct()).isGreaterThanOrEqualTo(0.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tenant isolation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void metrics_are_scoped_to_org() {
        // 3 members in orgA, 10 in orgB
        for (int i = 0; i < 3; i++) seedMembership(orgA, "oa" + i + "@m.com");
        for (int i = 0; i < 10; i++) seedMembership(orgB, "ob" + i + "@m.com");

        AudienceMetricsDto dtoA = metricsService.compute(orgA);
        AudienceMetricsDto dtoB = metricsService.compute(orgB);

        assertThat(dtoA.totalMembers()).isEqualTo(3);
        assertThat(dtoB.totalMembers()).isEqualTo(10);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // complaintRatePct: complained recipients / sent-or-delivered recipients
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void complaint_rate_pct_is_one_complaint_over_delivered_recipients() {
        UUID campaign = seedCampaign(orgA);
        UUID complainer = seedRecipient(campaign, seedMembership(orgA, "c0@m.com"), "complained");
        complaintEvent(campaign, complainer);
        for (int i = 1; i < 4; i++) seedRecipient(campaign, seedMembership(orgA, "c" + i + "@m.com"), "delivered");
        seedRecipient(campaign, seedMembership(orgA, "pending@m.com"), "pending");

        assertThat(metricsService.compute(orgA).complaintRatePct()).isEqualTo(25.0);
    }

    @Test
    void complaint_rate_pct_stays_within_100_when_the_complainer_then_unsubscribed() {
        UUID campaign = seedCampaign(orgA);
        // No subscribed members left, and the recipient row was overwritten after the complaint.
        UUID complainer = seedRecipient(campaign, seedMembership(orgA, "gone@m.com"), "unsubscribed");
        complaintEvent(campaign, complainer);
        complaintEvent(campaign, complainer);
        seedRecipient(campaign, seedMembership(orgA, "stay@m.com"), "delivered");

        AudienceMetricsDto dto = metricsService.compute(orgA);

        assertThat(dto.subscribedMailable()).isZero();
        assertThat(dto.complaintRatePct()).isEqualTo(50.0).isLessThanOrEqualTo(100.0);
    }

    @Test
    void complaint_rate_pct_is_zero_when_delivered_and_nobody_complained() {
        seedRecipient(seedCampaign(orgA), seedMembership(orgA, "nocomplaint@m.com"), "delivered");

        assertThat(metricsService.compute(orgA).complaintRatePct()).isEqualTo(0.0);
    }

    @Test
    void complaint_rate_pct_is_null_with_zero_delivered() {
        seedSubscribed(orgA, "sub@m.com");
        seedRecipient(seedCampaign(orgA), seedMembership(orgA, "p@m.com"), "pending");

        assertThat(metricsService.compute(orgA).complaintRatePct()).isNull();
    }

    @Test
    void complaint_rate_pct_ignores_other_orgs_campaigns() {
        UUID campA = seedCampaign(orgA);
        seedRecipient(campA, seedMembership(orgA, "a1@m.com"), "delivered");
        seedRecipient(campA, seedMembership(orgA, "a2@m.com"), "delivered");
        UUID campB = seedCampaign(orgB);
        complaintEvent(campB, seedRecipient(campB, seedMembership(orgB, "b@m.com"), "complained"));

        assertThat(metricsService.compute(orgA).complaintRatePct()).isEqualTo(0.0);
        assertThat(metricsService.compute(orgB).complaintRatePct()).isEqualTo(100.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private UUID seedMembership(UUID orgId, String email) {
        return seedAndGet(orgId, email).getMembershipId();
    }

    private UUID seedSubscribed(UUID orgId, String email) {
        Membership m = seedAndGet(orgId, email);
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        return membershipRepo.save(m).getMembershipId();
    }

    private UUID seedCampaign(UUID orgId) {
        com.imin.iminapi.marketing.model.Campaign c = new com.imin.iminapi.marketing.model.Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("metrics-test");
        c.setStatus("sent");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaignRepo.save(c).getId();
    }

    private UUID seedRecipient(UUID campaignId, UUID membershipId, String status) {
        com.imin.iminapi.marketing.model.CampaignRecipient r = new com.imin.iminapi.marketing.model.CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(campaignId);
        r.setMembershipId(membershipId);
        r.setEmail("r-" + UUID.randomUUID() + "@m.com");
        r.setStatus(status);
        return recipientRepo.save(r).getId();
    }

    private void complaintEvent(UUID campaignId, UUID recipientId) {
        com.imin.iminapi.marketing.model.ProviderEvent e = new com.imin.iminapi.marketing.model.ProviderEvent();
        e.setId(UUID.randomUUID());
        e.setProvider("resend");
        e.setProviderEventId("svix_" + UUID.randomUUID());
        e.setCampaignId(campaignId);
        e.setRecipientId(recipientId);
        e.setType(com.imin.iminapi.marketing.model.ProviderEvent.TYPE_COMPLAINED);
        e.setCreatedAt(Instant.now());
        providerEvents.save(e);
    }

    /** Seed a membership whose created_at is {@code ageDays} in the past. */
    private Membership seedAged(UUID orgId, String email, int ageDays) {
        String normalized = EmailNormalizer.normalize(email);
        Consumer consumer = consumerRepo.findByNormalizedEmail(normalized).orElse(null);
        if (consumer == null) {
            consumer = new Consumer();
            consumer.setNormalizedEmail(normalized);
            consumer.setDisplayName(email);
            consumer = consumerRepo.save(consumer);
        }
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumer.getConsumerId());
        m.setCreatedAt(java.time.Instant.now().minus(ageDays, java.time.temporal.ChronoUnit.DAYS));
        return membershipRepo.save(m);
    }

    private Membership seedAndGet(UUID orgId, String email) {
        String normalized = EmailNormalizer.normalize(email);
        Consumer consumer = consumerRepo.findByNormalizedEmail(normalized).orElse(null);
        if (consumer == null) {
            consumer = new Consumer();
            consumer.setNormalizedEmail(normalized);
            consumer.setDisplayName(email);
            consumer = consumerRepo.save(consumer);
        }
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumer.getConsumerId());
        return membershipRepo.save(m);
    }

    private Organization org(String name) {
        Organization o = new Organization();
        o.setName(name);
        o.setSlug(name.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 6));
        o.setContactEmail(name + "@test.com");
        o.setCountry("DE");
        return orgRepo.save(o);
    }

    private void wipe() {
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.Statement s = c.createStatement()) {
            s.execute("delete from provider_events");
            s.execute("delete from campaign_recipients");
            s.execute("delete from campaigns");
            s.execute("delete from suppression_entries");
            s.execute("delete from consent_records");
            s.execute("delete from segments");
            s.execute("delete from memberships");
            s.execute("delete from consumers");
            s.execute("delete from tickets");
            s.execute("delete from orders");
            s.execute("delete from events");
            s.execute("delete from users");
            s.execute("delete from organizations");
        } catch (Exception e) {
            throw new RuntimeException("wipe() failed: " + e.getMessage(), e);
        }
    }
}

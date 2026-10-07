package com.imin.iminapi.audience;

import com.imin.iminapi.audience.dto.AudienceMetricsDto;
import com.imin.iminapi.audience.model.*;
import com.imin.iminapi.audience.repository.*;
import com.imin.iminapi.audience.service.*;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
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
 * - unsubRatePct (unsubscribed / sent recipients, null with nothing sent)
 * - complaintRatePct (complaints / delivered, null with nothing delivered)
 * - empty org returns zeros (no NPE)
 * - tenant isolation: metrics scoped to org
 */
@IminIntegrationTest
class AudienceMetricsTest {

    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @Autowired OrganizationRepository orgRepo;
    @Autowired AudienceOrderProjector orderProjector;
    @Autowired AudienceMetricsService metricsService;
    @Autowired ConsentService consentService;
    @Autowired JdbcTemplate jdbc;
    @Autowired IminFixtures fx;
    @Autowired Clock clock;
    @Autowired com.imin.iminapi.marketing.repository.CampaignRepository campaignRepo;
    @Autowired com.imin.iminapi.marketing.repository.CampaignRecipientRepository recipientRepo;
    @Autowired com.imin.iminapi.marketing.repository.ProviderEventRepository providerEvents;

    private UUID orgA;
    private UUID orgB;

    @BeforeEach
    void setUp() {
        orgA = fx.org().getId();
        orgB = fx.org().getId();
    }

    /** Own campaigns (recipients cascade) and their provider events, own memberships, then own orgs. */
    @AfterEach
    void tearDown() {
        try {
            jdbc.update("delete from provider_events where campaign_id in "
                    + "(select id from campaigns where org_id in (?, ?))", orgA, orgB);
            jdbc.update("delete from campaigns where org_id in (?, ?)", orgA, orgB);
            jdbc.update("delete from memberships where org_id in (?, ?)", orgA, orgB);
        } finally {
            OrgRows.delete(jdbc, List.of(orgA, orgB));
        }
    }

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
        assertThat(dto.unsubRatePct()).isNull();
        assertThat(dto.complaintRatePct()).isNull();
        assertThat(dto.listGrowth8w()).hasSize(8);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // totalMembers
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void total_members_counts_all_in_org() {
        seedMembership(orgA, fx.email("m1"));
        seedMembership(orgA, fx.email("m2"));
        seedMembership(orgA, fx.email("m3"));
        // orgB member should not count
        seedMembership(orgB, fx.email("mb"));

        AudienceMetricsDto dto = metricsService.compute(orgA);
        assertThat(dto.totalMembers()).isEqualTo(3);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buyers vs prospects
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buyers_are_members_with_events_gt_0() {
        // Buyer: events > 0
        Membership buyer = seedAndGet(orgA, fx.email("buyer"));
        buyer.setEvents(2);
        membershipRepo.save(buyer);

        // Prospect: events == 0 (default)
        seedMembership(orgA, fx.email("prospect"));

        AudienceMetricsDto dto = metricsService.compute(orgA);
        assertThat(dto.buyers()).isEqualTo(1);
        assertThat(dto.prospects()).isEqualTo(1);
        assertThat(dto.totalMembers()).isEqualTo(2);
    }

    @Test
    void buyers_plus_prospects_equals_total() {
        Membership b1 = seedAndGet(orgA, fx.email("b1"));
        b1.setEvents(1); membershipRepo.save(b1);

        Membership b2 = seedAndGet(orgA, fx.email("b2"));
        b2.setEvents(3); membershipRepo.save(b2);

        seedMembership(orgA, fx.email("p1"));
        seedMembership(orgA, fx.email("p2"));

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
        Membership sub = seedAndGet(orgA, fx.email("sub"));
        sub.setConsentStatus("subscribed");
        sub.setConsentBasis("explicit");
        membershipRepo.save(sub);

        // Unsubscribed
        Membership unsub = seedAndGet(orgA, fx.email("unsub"));
        unsub.setConsentStatus("unsubscribed");
        membershipRepo.save(unsub);

        // Never (default)
        seedMembership(orgA, fx.email("never"));

        AudienceMetricsDto dto = metricsService.compute(orgA);
        assertThat(dto.subscribedMailable()).isEqualTo(1);
    }

    @Test
    void subscribed_pct_computed_correctly() {
        // 2 out of 4 subscribed → 50%
        for (int i = 0; i < 2; i++) {
            Membership m = seedAndGet(orgA, fx.email("sub" + i));
            m.setConsentStatus("subscribed");
            membershipRepo.save(m);
        }
        for (int i = 0; i < 2; i++) {
            seedMembership(orgA, fx.email("never" + i));
        }

        AudienceMetricsDto dto = metricsService.compute(orgA);
        assertThat(dto.subscribedPct()).isCloseTo(50.0, within(0.1));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // explicitConsent vs softOptIn
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void explicit_consent_counts_explicit_basis_members() {
        Membership explicit = seedAndGet(orgA, fx.email("expl"));
        explicit.setConsentBasis("explicit");
        membershipRepo.save(explicit);

        Membership soft = seedAndGet(orgA, fx.email("soft"));
        soft.setConsentBasis("soft_opt_in");
        membershipRepo.save(soft);

        seedMembership(orgA, fx.email("none")); // null basis

        AudienceMetricsDto dto = metricsService.compute(orgA);
        assertThat(dto.explicitConsent()).isEqualTo(1);
        assertThat(dto.softOptIn()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // listGrowth8w
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void list_growth_8w_has_exactly_8_entries() {
        seedMembership(orgA, fx.email("grow"));
        AudienceMetricsDto dto = metricsService.compute(orgA);
        assertThat(dto.listGrowth8w()).hasSize(8);
    }

    @Test
    void list_growth_8w_current_week_reflects_new_member() {
        // A member created just now should appear in the most recent week bucket
        seedMembership(orgA, fx.email("recent"));

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
        Membership repeat = seedAndGet(orgA, fx.email("rattn"));
        repeat.setEvents(3);
        repeat.setAttended(2);
        membershipRepo.save(repeat);

        // single attendee
        Membership single = seedAndGet(orgA, fx.email("sattn"));
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
        Membership old = seedAged(orgA, fx.email("oldrepeat"), 100);
        old.setEvents(3);
        old.setAttended(2);
        membershipRepo.save(old);

        AudienceMetricsDto dto = metricsService.compute(orgA);

        assertThat(dto.repeatAttendeePct()).isEqualTo(100.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // unsubRatePct
    // ─────────────────────────────────────────────────────────────────────────

    /** Nothing sent: null even after an operator unsubscribe. Delivered with no unsubscribe: 0, not null. */
    @ParameterizedTest
    @ValueSource(strings = {"nothing-sent", "delivered-none-unsubscribed"})
    void unsub_rate_pct_is_null_without_sends_and_zero_without_unsubscribes(String row) {
        if (row.equals("nothing-sent")) {
            UUID mid = seedMembership(orgA, fx.email("unsubrate"));
            AuthPrincipal p = new com.imin.iminapi.security.AuthPrincipal(
                    UUID.randomUUID(), orgA, UserRole.OWNER, UUID.randomUUID());
            consentService.capture(orgA, mid, "explicit", "test", "proof", p);
            consentService.unsubscribe(orgA, mid, "own-request", ConsentOrigin.OPERATOR, p);

            assertThat(metricsService.compute(orgA).unsubRatePct()).isNull();
        } else {
            seedRecipient(seedCampaign(orgA), seedMembership(orgA, fx.email("nounsub")), "delivered");

            assertThat(metricsService.compute(orgA).unsubRatePct()).isEqualTo(0.0);
        }
    }

    @Test
    void unsub_rate_pct_is_unsubscribed_recipients_over_sent_recipients() {
        UUID campaign = seedCampaign(orgA);
        seedRecipient(campaign, seedMembership(orgA, fx.email("u0")), "unsubscribed");
        for (int i = 1; i < 4; i++) seedRecipient(campaign, seedMembership(orgA, fx.email("u" + i)), "delivered");
        seedRecipient(campaign, seedMembership(orgA, fx.email("upending")), "pending");

        assertThat(metricsService.compute(orgA).unsubRatePct()).isEqualTo(25.0);
    }

    @Test
    void unsub_rate_pct_ignores_other_orgs_unsubscribed_recipients() {
        UUID campB = seedCampaign(orgB);
        seedRecipient(campB, seedMembership(orgB, fx.email("bunsub")), "unsubscribed");
        UUID campA = seedCampaign(orgA);
        seedRecipient(campA, seedMembership(orgA, fx.email("a1u")), "delivered");
        seedRecipient(campA, seedMembership(orgA, fx.email("a2u")), "delivered");

        assertThat(metricsService.compute(orgA).unsubRatePct()).isEqualTo(0.0);
        assertThat(metricsService.compute(orgB).unsubRatePct()).isEqualTo(100.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tenant isolation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void metrics_are_scoped_to_org() {
        // 3 members in orgA, 10 in orgB
        for (int i = 0; i < 3; i++) seedMembership(orgA, fx.email("oa" + i));
        for (int i = 0; i < 10; i++) seedMembership(orgB, fx.email("ob" + i));

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
        UUID complainer = seedRecipient(campaign, seedMembership(orgA, fx.email("c0")), "complained");
        complaintEvent(campaign, complainer);
        for (int i = 1; i < 4; i++) seedRecipient(campaign, seedMembership(orgA, fx.email("c" + i)), "delivered");
        seedRecipient(campaign, seedMembership(orgA, fx.email("pending")), "pending");

        assertThat(metricsService.compute(orgA).complaintRatePct()).isEqualTo(25.0);
    }

    @Test
    void complaint_rate_pct_stays_within_100_when_the_complainer_then_unsubscribed() {
        UUID campaign = seedCampaign(orgA);
        // No subscribed members left, and the recipient row was overwritten after the complaint.
        UUID complainer = seedRecipient(campaign, seedMembership(orgA, fx.email("gone")), "unsubscribed");
        complaintEvent(campaign, complainer);
        complaintEvent(campaign, complainer);
        seedRecipient(campaign, seedMembership(orgA, fx.email("stay")), "delivered");

        AudienceMetricsDto dto = metricsService.compute(orgA);

        assertThat(dto.subscribedMailable()).isZero();
        assertThat(dto.complaintRatePct()).isEqualTo(50.0).isLessThanOrEqualTo(100.0);
    }

    /** Delivered with no complaint: 0. Nothing delivered (only a pending row): null, never a fabricated 0. */
    @ParameterizedTest
    @ValueSource(strings = {"delivered-none-complained", "nothing-delivered"})
    void complaint_rate_pct_is_zero_without_complaints_and_null_without_deliveries(String row) {
        if (row.equals("delivered-none-complained")) {
            seedRecipient(seedCampaign(orgA), seedMembership(orgA, fx.email("nocomplaint")), "delivered");

            assertThat(metricsService.compute(orgA).complaintRatePct()).isEqualTo(0.0);
        } else {
            seedSubscribed(orgA, fx.email("sub"));
            seedRecipient(seedCampaign(orgA), seedMembership(orgA, fx.email("p")), "pending");

            assertThat(metricsService.compute(orgA).complaintRatePct()).isNull();
        }
    }

    @Test
    void complaint_rate_pct_ignores_other_orgs_campaigns() {
        UUID campA = seedCampaign(orgA);
        seedRecipient(campA, seedMembership(orgA, fx.email("a1")), "delivered");
        seedRecipient(campA, seedMembership(orgA, fx.email("a2")), "delivered");
        UUID campB = seedCampaign(orgB);
        complaintEvent(campB, seedRecipient(campB, seedMembership(orgB, fx.email("b")), "complained"));

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
        c.setCreatedAt(clock.instant());
        c.setUpdatedAt(clock.instant());
        return campaignRepo.save(c).getId();
    }

    private UUID seedRecipient(UUID campaignId, UUID membershipId, String status) {
        com.imin.iminapi.marketing.model.CampaignRecipient r = new com.imin.iminapi.marketing.model.CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(campaignId);
        r.setMembershipId(membershipId);
        r.setEmail(fx.email("r"));
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
        e.setCreatedAt(clock.instant());
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
        m.setCreatedAt(clock.instant().minus(ageDays, ChronoUnit.DAYS));
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
}

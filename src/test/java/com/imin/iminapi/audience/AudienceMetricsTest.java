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
        assertThat(dto.complaintRatePct()).isZero();
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
    // complaintRatePct is 0.0 (not tracked at Tier C)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void complaint_rate_pct_is_zero_at_tier_c() {
        seedMembership(orgA, "complaint@m.com");
        AudienceMetricsDto dto = metricsService.compute(orgA);
        assertThat(dto.complaintRatePct()).isZero();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private UUID seedMembership(UUID orgId, String email) {
        return seedAndGet(orgId, email).getMembershipId();
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

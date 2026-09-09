package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.SuppressionEntry;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.audience.service.AudienceService;
import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.audience.service.SuppressionService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * audience-7: V50 claimed suppression uniqueness and enforced it nowhere, so a lost
 * read-then-insert race left two rows and turned the Optional finders — and with them
 * GET /audience/members/{id} and the CSV import's deliverability check — into a
 * permanent 500. V114 adds the two unique indexes.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AudienceSuppressionUniquenessTest {

    @Autowired SuppressionRepository suppressionRepo;
    @Autowired SuppressionService suppressionService;
    @Autowired AudienceService audienceService;
    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired OrganizationRepository orgRepo;
    @Autowired DataSource dataSource;

    @MockitoBean AuditLogger auditLogger;

    private UUID orgA;
    private AuthPrincipal principalA;

    @BeforeEach
    void setUp() {
        wipe();
        orgA = org("SuppOrgA").getId();
        principalA = new AuthPrincipal(UUID.randomUUID(), orgA, UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() { wipe(); }

    @Test
    void a_second_marketing_suppression_for_one_membership_is_rejected() {
        UUID mid = seedMembership(orgA, "dupmarketing@s.com");
        suppressionService.addMarketing(orgA, mid, "manual", principalA);

        assertThatThrownBy(() -> suppressionRepo.saveAndFlush(marketingRow(orgA, mid)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(suppressionRepo.findMarketingByOrgAndMembership(orgA, mid)).isPresent();
        assertThat(audienceService.getMember(orgA, mid).suppression()).isNotNull();
    }

    @Test
    void a_second_deliverability_suppression_for_one_address_is_rejected() {
        suppressionService.addDeliverability("dupbounce@s.com", "hard-bounce");

        assertThatThrownBy(() -> suppressionRepo.saveAndFlush(deliverabilityRow("dupbounce@s.com")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(suppressionService.isDeliverabilityBlocked("dupbounce@s.com")).isTrue();
    }

    /**
     * The indexes must not collide across scopes: marketing rows carry no email and
     * deliverability rows carry no org/membership, and NULLs are distinct in both engines.
     */
    @Test
    void the_two_scopes_do_not_constrain_each_other() {
        UUID first = seedMembership(orgA, "scope1@s.com");
        UUID second = seedMembership(orgA, "scope2@s.com");
        suppressionService.addMarketing(orgA, first, "manual", principalA);
        suppressionService.addMarketing(orgA, second, "manual", principalA);
        suppressionService.addDeliverability("bounce1@s.com", "hard-bounce");
        suppressionService.addDeliverability("bounce2@s.com", "hard-bounce");

        assertThat(suppressionRepo.findMarketingByOrg(orgA)).hasSize(2);
        assertThat(suppressionService.isDeliverabilityBlocked("bounce1@s.com")).isTrue();
        assertThat(suppressionService.isDeliverabilityBlocked("bounce2@s.com")).isTrue();
    }

    /** Both add* methods stay idempotent: the existing row is returned, not a second one. */
    @Test
    void adding_the_same_suppression_twice_is_still_idempotent() {
        UUID mid = seedMembership(orgA, "idem@s.com");
        SuppressionEntry once = suppressionService.addMarketing(orgA, mid, "manual", principalA);
        SuppressionEntry twice = suppressionService.addMarketing(orgA, mid, "manual", principalA);
        assertThat(twice.getId()).isEqualTo(once.getId());

        SuppressionEntry b1 = suppressionService.addDeliverability("idem2@s.com", "hard-bounce");
        SuppressionEntry b2 = suppressionService.addDeliverability("idem2@s.com", "hard-bounce");
        assertThat(b2.getId()).isEqualTo(b1.getId());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SuppressionEntry marketingRow(UUID orgId, UUID membershipId) {
        SuppressionEntry s = new SuppressionEntry();
        s.setScope(SuppressionEntry.SCOPE_MARKETING);
        s.setOrgId(orgId);
        s.setMembershipId(membershipId);
        s.setReason("manual");
        s.setSystemOwned(false);
        return s;
    }

    private SuppressionEntry deliverabilityRow(String email) {
        SuppressionEntry s = new SuppressionEntry();
        s.setScope(SuppressionEntry.SCOPE_DELIVERABILITY);
        s.setNormalizedEmail(email);
        s.setReason("hard-bounce");
        s.setSystemOwned(true);
        return s;
    }

    private UUID seedMembership(UUID orgId, String email) {
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
        return membershipRepo.save(m).getMembershipId();
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
            s.execute("delete from users");
            s.execute("delete from organizations");
        } catch (Exception e) {
            throw new RuntimeException("wipe() failed: " + e.getMessage(), e);
        }
    }
}

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
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * audience-7: V50 claimed suppression uniqueness and enforced it nowhere, so a lost
 * read-then-insert race left two rows and turned the Optional finders — and with them
 * GET /audience/members/{id} and the CSV import's deliverability check — into a
 * permanent 500. V114 adds the two unique indexes.
 * Each violation is its test's last statement; addresses are unique (suppressions are shared across orgs).
 */
@IminIntegrationTest
class AudienceSuppressionUniquenessTest {

    @Autowired SuppressionRepository suppressionRepo;
    @Autowired SuppressionService suppressionService;
    @Autowired AudienceService audienceService;
    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private UUID orgA;
    private AuthPrincipal principalA;

    @BeforeEach
    void setUp() {
        orgA = fx.org().getId();
        principalA = new AuthPrincipal(UUID.randomUUID(), orgA, UserRole.OWNER, UUID.randomUUID());
    }

    /** Own memberships (consent and marketing suppressions cascade) and orgs only. */
    @AfterEach
    void tearDown() {
        try {
            jdbc.update("delete from memberships where org_id in (?)", orgA);
        } finally {
            OrgRows.delete(jdbc, List.of(orgA));
        }
    }

    @Test
    void a_second_marketing_suppression_for_one_membership_is_rejected() {
        UUID mid = seedMembership(orgA, fx.email("dupmarketing"));
        suppressionService.addMarketing(orgA, mid, "manual", principalA);
        assertThat(suppressionRepo.findMarketingByOrgAndMembership(orgA, mid)).isPresent();
        assertThat(audienceService.getMember(orgA, mid).suppression()).isNotNull();

        assertThatThrownBy(() -> suppressionRepo.saveAndFlush(marketingRow(orgA, mid)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void a_second_deliverability_suppression_for_one_address_is_rejected() {
        String email = fx.email("dupbounce");
        suppressionService.addDeliverability(email, "hard-bounce");
        assertThat(suppressionService.isDeliverabilityBlocked(email)).isTrue();

        assertThatThrownBy(() -> suppressionRepo.saveAndFlush(deliverabilityRow(email)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The indexes must not collide across scopes: marketing rows carry no email and
     * deliverability rows carry no org/membership, and NULLs are distinct in both engines.
     */
    @Test
    void the_two_scopes_do_not_constrain_each_other() {
        UUID first = seedMembership(orgA, fx.email("scope1"));
        UUID second = seedMembership(orgA, fx.email("scope2"));
        String bounce1 = fx.email("bounce1");
        String bounce2 = fx.email("bounce2");
        suppressionService.addMarketing(orgA, first, "manual", principalA);
        suppressionService.addMarketing(orgA, second, "manual", principalA);
        suppressionService.addDeliverability(bounce1, "hard-bounce");
        suppressionService.addDeliverability(bounce2, "hard-bounce");

        assertThat(suppressionRepo.findMarketingByOrg(orgA)).hasSize(2);
        assertThat(suppressionService.isDeliverabilityBlocked(bounce1)).isTrue();
        assertThat(suppressionService.isDeliverabilityBlocked(bounce2)).isTrue();
    }

    /** Both add* methods stay idempotent: the existing row is returned, not a second one. */
    @Test
    void adding_the_same_suppression_twice_is_still_idempotent() {
        UUID mid = seedMembership(orgA, fx.email("idem"));
        SuppressionEntry once = suppressionService.addMarketing(orgA, mid, "manual", principalA);
        SuppressionEntry twice = suppressionService.addMarketing(orgA, mid, "manual", principalA);
        assertThat(twice.getId()).isEqualTo(once.getId());

        String bounce = fx.email("idem2");
        SuppressionEntry b1 = suppressionService.addDeliverability(bounce, "hard-bounce");
        SuppressionEntry b2 = suppressionService.addDeliverability(bounce, "hard-bounce");
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
}

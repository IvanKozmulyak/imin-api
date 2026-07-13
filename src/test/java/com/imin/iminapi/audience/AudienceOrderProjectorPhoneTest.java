package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §4: the projector carries phone + opt-in from the order onto the
 * membership. Exercised via the package-visible upsertMembership seam.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AudienceOrderProjectorPhoneTest {

    @Autowired AudienceOrderProjector projector;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired OrderRepository orders;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        // Marker Repository<> — wipe via JDBC (audience-suite pattern).
        jdbc.update("DELETE FROM memberships");
        jdbc.update("DELETE FROM consumers");
        orders.deleteAll();
    }

    @Test
    void upsertMembership_withPhoneAndOptIn_projectsPhoneAndSmsSubscribed() {
        UUID orgId = UUID.randomUUID();

        projector.upsertMembership(orgId, "buyer@example.com", "Buyer",
                "+380671234567", true);

        UUID consumerId = consumers.findByNormalizedEmail("buyer@example.com").orElseThrow().getConsumerId();
        Membership m = memberships.findByOrgIdAndConsumerId(orgId, consumerId).orElseThrow();
        assertThat(m.getPhoneE164()).isEqualTo("+380671234567");
        assertThat(m.getSmsConsentStatus()).isEqualTo("subscribed");
        assertThat(m.getSmsConsentBasis()).isEqualTo("explicit");
    }

    @Test
    void upsertMembership_withPhoneButNoOptIn_projectsPhoneOnly() {
        UUID orgId = UUID.randomUUID();

        projector.upsertMembership(orgId, "buyer2@example.com", "Buyer2",
                "+380671234500", false);

        UUID consumerId = consumers.findByNormalizedEmail("buyer2@example.com").orElseThrow().getConsumerId();
        Membership m = memberships.findByOrgIdAndConsumerId(orgId, consumerId).orElseThrow();
        assertThat(m.getPhoneE164()).isEqualTo("+380671234500");
        assertThat(m.getSmsConsentStatus()).isEqualTo("never");
        assertThat(m.getSmsConsentBasis()).isNull();
    }

    @Test
    void upsertMembership_noPhone_leavesPhoneNull() {
        UUID orgId = UUID.randomUUID();

        projector.upsertMembership(orgId, "buyer3@example.com", "Buyer3", null, false);

        UUID consumerId = consumers.findByNormalizedEmail("buyer3@example.com").orElseThrow().getConsumerId();
        Membership m = memberships.findByOrgIdAndConsumerId(orgId, consumerId).orElseThrow();
        assertThat(m.getPhoneE164()).isNull();
        assertThat(m.getSmsConsentStatus()).isEqualTo("never");
    }
}

package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §4: the projector carries phone + opt-in from the order onto the
 * membership. Exercised via the package-visible upsertMembership seam; addresses and numbers are unique.
 */
@IminIntegrationTest
class AudienceOrderProjectorPhoneTest {

    @Autowired AudienceOrderProjector projector;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired IminFixtures fx;

    private static String phone() {
        return "+38067" + ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000);
    }

    @Test
    void upsertMembership_withPhoneAndOptIn_projectsPhoneAndSmsSubscribed() {
        UUID orgId = UUID.randomUUID();

        String email = fx.email("buyer");
        String phone = phone();
        projector.upsertMembership(orgId, email, "Buyer", phone, true);

        UUID consumerId = consumers.findByNormalizedEmail(email).orElseThrow().getConsumerId();
        Membership m = memberships.findByOrgIdAndConsumerId(orgId, consumerId).orElseThrow();
        assertThat(m.getPhoneE164()).isEqualTo(phone);
        assertThat(m.getSmsConsentStatus()).isEqualTo("subscribed");
        assertThat(m.getSmsConsentBasis()).isEqualTo("explicit");
    }

    @Test
    void upsertMembership_withPhoneButNoOptIn_projectsPhoneOnly() {
        UUID orgId = UUID.randomUUID();

        String email = fx.email("buyer2");
        String phone = phone();
        projector.upsertMembership(orgId, email, "Buyer2", phone, false);

        UUID consumerId = consumers.findByNormalizedEmail(email).orElseThrow().getConsumerId();
        Membership m = memberships.findByOrgIdAndConsumerId(orgId, consumerId).orElseThrow();
        assertThat(m.getPhoneE164()).isEqualTo(phone);
        assertThat(m.getSmsConsentStatus()).isEqualTo("never");
        assertThat(m.getSmsConsentBasis()).isNull();
    }

    @Test
    void upsertMembership_noPhone_leavesPhoneNull() {
        UUID orgId = UUID.randomUUID();

        String email = fx.email("buyer3");
        projector.upsertMembership(orgId, email, "Buyer3", null, false);

        UUID consumerId = consumers.findByNormalizedEmail(email).orElseThrow().getConsumerId();
        Membership m = memberships.findByOrgIdAndConsumerId(orgId, consumerId).orElseThrow();
        assertThat(m.getPhoneE164()).isNull();
        assertThat(m.getSmsConsentStatus()).isEqualTo("never");
    }
}

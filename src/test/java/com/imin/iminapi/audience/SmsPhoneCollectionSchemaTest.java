package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V55 schema smoke test: the phone-collection columns exist on memberships and
 * round-trip through JPA on H2 (Flyway applies V55 at context start).
 *
 * <p>Note: {@link MembershipRepository} is a tenant-scoped marker repository that
 * does not expose an unscoped {@code findById}; reads go through the declared
 * {@code findByIdAndOrgId(id, orgId)} finder. {@code memberships.consumer_id} has a
 * FK to {@code consumers}, so a parent {@link Consumer} is persisted first.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class SmsPhoneCollectionSchemaTest {

    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsumerRepository consumerRepo;

    private UUID newConsumerId() {
        Consumer c = new Consumer();
        c.setNormalizedEmail("sms-schema-" + UUID.randomUUID() + "@example.test");
        return consumerRepo.save(c).getConsumerId();
    }

    @Test
    void membership_phoneAndSmsConsentColumns_roundTrip() {
        UUID orgId = UUID.randomUUID();
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(newConsumerId());
        m.setPhoneE164("+380671234567");
        m.setSmsConsentStatus("subscribed");
        m.setSmsConsentBasis("explicit");
        Membership saved = membershipRepo.save(m);

        Membership reloaded = membershipRepo.findByIdAndOrgId(saved.getMembershipId(), orgId).orElseThrow();
        assertThat(reloaded.getPhoneE164()).isEqualTo("+380671234567");
        assertThat(reloaded.getSmsConsentStatus()).isEqualTo("subscribed");
        assertThat(reloaded.getSmsConsentBasis()).isEqualTo("explicit");
    }

    @Test
    void membership_smsConsentStatusDefaultsToNever() {
        UUID orgId = UUID.randomUUID();
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(newConsumerId());
        Membership saved = membershipRepo.save(m);

        Membership reloaded = membershipRepo.findByIdAndOrgId(saved.getMembershipId(), orgId).orElseThrow();
        assertThat(reloaded.getSmsConsentStatus()).isEqualTo("never");
        assertThat(reloaded.getPhoneE164()).isNull();
        assertThat(reloaded.getSmsConsentBasis()).isNull();
    }
}

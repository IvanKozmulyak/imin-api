package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §2.2 / §7: ConsentService.capture becomes channel-aware. An SMS capture
 * writes a channel='sms' proof row and the membership's SMS consent state; the
 * default (email) overload keeps existing behaviour.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class ConsentServiceChannelTest {

    @Autowired ConsentService consentService;
    @Autowired MembershipRepository membershipRepo;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired ConsentRecordRepository consentRepo;
    @MockitoBean AuditLogger auditLogger;

    private UUID seedMembership(UUID orgId) {
        Consumer c = new Consumer();
        c.setNormalizedEmail("buyer-" + UUID.randomUUID() + "@example.com");
        c.setDisplayName("Buyer");
        c = consumerRepo.save(c);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        return membershipRepo.save(m).getMembershipId();
    }

    @Test
    void captureSms_writesSmsProofRowAndMembershipState() {
        UUID orgId = UUID.randomUUID();
        UUID membershipId = seedMembership(orgId);

        consentService.capture(orgId, membershipId, "explicit", "order_confirmation",
                "Text me about this organizer's events", "sms", null);

        Membership m = membershipRepo.findByIdAndOrgId(membershipId, orgId).orElseThrow();
        assertThat(m.getSmsConsentStatus()).isEqualTo("subscribed");
        assertThat(m.getSmsConsentBasis()).isEqualTo("explicit");
        // email consent state must be untouched by an SMS capture
        assertThat(m.getConsentStatus()).isEqualTo("never");

        assertThat(consentRepo.findByMembershipId(membershipId))
                .anySatisfy(r -> {
                    assertThat(r.getChannel()).isEqualTo("sms");
                    assertThat(r.getStatus()).isEqualTo("subscribed");
                    assertThat(r.getLawfulBasis()).isEqualTo("explicit");
                    assertThat(r.getSource()).isEqualTo("order_confirmation");
                    assertThat(r.getProofText()).isEqualTo("Text me about this organizer's events");
                });
    }

    @Test
    void captureEmailDefaultOverload_stillWritesEmailChannel() {
        UUID orgId = UUID.randomUUID();
        UUID membershipId = seedMembership(orgId);

        consentService.capture(orgId, membershipId, "explicit", "audience_ui", "proof", null);

        Membership m = membershipRepo.findByIdAndOrgId(membershipId, orgId).orElseThrow();
        assertThat(m.getConsentStatus()).isEqualTo("subscribed");
        assertThat(m.getConsentBasis()).isEqualTo("explicit");
        assertThat(m.getSmsConsentStatus()).isEqualTo("never");

        assertThat(consentRepo.findByMembershipId(membershipId))
                .anySatisfy(r -> assertThat(r.getChannel()).isEqualTo("email"));
    }
}

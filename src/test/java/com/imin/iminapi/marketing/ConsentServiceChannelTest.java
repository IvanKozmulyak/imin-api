package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class ConsentServiceChannelTest {

    @Autowired ConsentService consentService;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired ConsentRecordRepository consentRecords;
    @MockitoBean com.imin.iminapi.service.audit.AuditLogger auditLogger;

    @Test
    void unsubscribe_withSmsChannel_writesSmsConsentRecord() {
        UUID orgId = UUID.randomUUID();
        // Consumer / Membership ids are @GeneratedValue — let JPA assign them and
        // read them back (matches the audience-service test style, imin-api scout §11).
        Consumer c = new Consumer();
        c.setNormalizedEmail("chan-" + UUID.randomUUID() + "@example.com");
        c = consumers.save(c);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        UUID membershipId = memberships.save(m).getMembershipId();

        AuthPrincipal system = new AuthPrincipal(null, orgId, UserRole.MEMBER, null);
        consentService.unsubscribe(orgId, membershipId, "one_click", "sms", system);

        Membership after = memberships.findByIdAndOrgId(membershipId, orgId).orElseThrow();
        // Channel isolation (§2.2): SMS unsubscribe clears the SMS state only —
        // the email consent pair is untouched.
        assertThat(after.getSmsConsentStatus()).isEqualTo("unsubscribed");
        assertThat(after.getConsentStatus()).isEqualTo("subscribed");
        assertThat(consentRecords.findByMembershipId(membershipId).stream()
                .anyMatch(r -> "sms".equals(r.getChannel())
                        && "unsubscribed".equals(r.getStatus()))).isTrue();
    }

    @Test
    void unsubscribe_backCompatOverload_defaultsToEmailChannel() {
        UUID orgId = UUID.randomUUID();
        Consumer c = new Consumer();
        c.setNormalizedEmail("compat-" + UUID.randomUUID() + "@example.com");
        c = consumers.save(c);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        UUID membershipId = memberships.save(m).getMembershipId();

        AuthPrincipal system = new AuthPrincipal(null, orgId, UserRole.MEMBER, null);
        consentService.unsubscribe(orgId, membershipId, "dsar_object", system);

        assertThat(consentRecords.findByMembershipId(membershipId).stream()
                .anyMatch(r -> "email".equals(r.getChannel())
                        && "unsubscribed".equals(r.getStatus()))).isTrue();
    }
}

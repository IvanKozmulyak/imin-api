package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.DsarService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class DsarCampaignRecipientRedactionTest {

    @Autowired DsarService dsarService;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired CampaignRepository campaigns;
    @MockitoBean com.imin.iminapi.service.audit.AuditLogger auditLogger;

    @Test
    void erase_nullsRecipientPii_keepsStatusRow() {
        UUID orgId = UUID.randomUUID();
        // consumer_id / membership_id are @GeneratedValue(UUID): let JPA assign them and read
        // back the generated ids. Setting them manually makes Spring Data treat the entity as
        // detached and issue a merge/UPDATE (row-not-found), which is the wrong intent here.
        Consumer c = new Consumer();
        c.setNormalizedEmail("erase-" + UUID.randomUUID() + "@example.com");
        c = consumers.save(c);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        m.setStatus("erase_pending");
        m = memberships.save(m);

        // campaign_recipients.campaign_id is NOT NULL REFERENCES campaigns(id) (V53), enforced by
        // H2 (MODE=PostgreSQL). Persist a real campaign so recipients.save(r) does not fail at flush
        // with a 23506 referential-integrity violation before executeErase runs. This also matches
        // the DSAR intent: an anonymized recipient row belonging to a real campaign.
        Campaign camp = new Campaign();
        camp.setId(UUID.randomUUID());
        camp.setOrgId(orgId);
        camp.setChannel("email");
        camp.setName("t");
        camp.setStatus("sent");
        // created_at / updated_at are NOT NULL with no @PrePersist default (Phase-1 convention).
        camp.setCreatedAt(Instant.now());
        camp.setUpdatedAt(Instant.now());
        campaigns.save(camp);

        CampaignRecipient r = new CampaignRecipient();
        UUID recipientId = UUID.randomUUID();
        r.setId(recipientId);
        r.setCampaignId(camp.getId());
        r.setMembershipId(m.getMembershipId());
        r.setEmail(c.getNormalizedEmail());
        r.setStatus("sent");
        recipients.save(r);

        dsarService.executeErase(orgId, m.getMembershipId(),
                new AuthPrincipal(null, orgId, UserRole.MEMBER, null));

        CampaignRecipient after = recipients.findById(recipientId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("sent");       // audit aggregate survives
        assertThat(after.getEmail()).isNull();                  // PII redacted
        assertThat(after.getMembershipId()).isNull();           // FK ON DELETE SET NULL
    }
}

package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class CampaignRecipientRepositoryTest {

    @Autowired CampaignRecipientRepository repo;
    @Autowired CampaignRepository campaignRepository;

    /**
     * campaign_recipients.campaign_id is UUID NOT NULL REFERENCES campaigns(id) (V53).
     * H2 2.4.240 in MODE=PostgreSQL ENFORCES that FK, so a recipient row whose campaign_id
     * has no matching campaigns row fails at flush with "Referential integrity constraint
     * violation … FOREIGN KEY(campaign_id) REFERENCES public.campaigns(id) [23506-240]".
     * Every test therefore seeds a real campaign first and uses its id.
     */
    private UUID persistCampaign() {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(UUID.randomUUID());
        c.setChannel("email");
        c.setName("t");
        c.setStatus("sending");
        // created_at / updated_at are NOT NULL with no @PrePersist default (Phase-1 convention).
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaignRepository.save(c);
        return c.getId();
    }

    @Test
    void savesPendingRow_andCountsByStatus() {
        UUID campaignId = persistCampaign();
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(campaignId);
        r.setMembershipId(null); // membership_id is nullable (ON DELETE SET NULL, DSAR-safe)
        r.setEmail("ada@example.com");
        r.setStatus("pending");
        r.setAttemptCount((short) 0);
        r.setLastEventAt(Instant.now());
        repo.save(r);

        List<CampaignRecipient> pending = repo.findByCampaignIdAndStatus(campaignId, "pending");
        assertThat(pending).hasSize(1);
        assertThat(repo.countByCampaignIdAndStatus(campaignId, "pending")).isEqualTo(1L);
    }

    @Test
    void toleratesMultipleNullMembershipIds() {
        // Postgres UNIQUE(campaign_id, membership_id) tolerates multiple NULLs (spec §2.2).
        UUID campaignId = persistCampaign();
        for (int i = 0; i < 3; i++) {
            CampaignRecipient r = new CampaignRecipient();
            r.setId(UUID.randomUUID());
            r.setCampaignId(campaignId);
            r.setMembershipId(null);
            r.setEmail("addr" + i + "@example.com");
            r.setStatus("pending");
            repo.save(r);
        }
        assertThat(repo.countByCampaignIdAndStatus(campaignId, "pending")).isEqualTo(3L);
    }
}

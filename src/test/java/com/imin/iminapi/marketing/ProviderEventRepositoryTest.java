package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.ProviderEvent;
import com.imin.iminapi.marketing.repository.ProviderEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class ProviderEventRepositoryTest {

    @Autowired ProviderEventRepository repo;

    private ProviderEvent evt(UUID campaignId, String type) {
        ProviderEvent e = new ProviderEvent();
        e.setId(UUID.randomUUID());
        e.setProvider("resend");
        e.setProviderEventId("svix_" + UUID.randomUUID());
        e.setCampaignId(campaignId);
        e.setType(type);
        e.setCreatedAt(Instant.now());
        return repo.save(e);
    }

    @Test
    void countsComplaintsAndTotalPerCampaign() {
        UUID campaignId = UUID.randomUUID();
        evt(campaignId, "email.delivered");
        evt(campaignId, "email.delivered");
        evt(campaignId, "email.complained");
        // a different campaign's complaint must not leak in
        evt(UUID.randomUUID(), "email.complained");

        long complaints = repo.countByCampaignIdAndType(campaignId, "email.complained");
        long delivered = repo.countByCampaignIdAndType(campaignId, "email.delivered");
        assertThat(complaints).isEqualTo(1);
        assertThat(delivered).isEqualTo(2);
    }
}

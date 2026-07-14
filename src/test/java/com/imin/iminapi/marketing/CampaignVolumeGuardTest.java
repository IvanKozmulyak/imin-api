package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignVolumeGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class CampaignVolumeGuardTest {

    @Autowired CampaignVolumeGuard guard;
    @Autowired CampaignRecipientRepository recipientRepo;
    @Autowired CampaignRepository campaignRepo;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;

    /**
     * campaign_recipients has NOT-NULL FKs enforced by H2 (MODE=PostgreSQL):
     * campaign_id REFERENCES campaigns(id) and membership_id REFERENCES
     * memberships(membership_id) (V53). So a sent row must reference a real
     * campaign and a real membership — bare random UUIDs fail at flush with a
     * 23506 referential-integrity violation. Seed both, then return the
     * generated membership_id the guard queries by.
     */
    private UUID sentMembership(Instant sentAt) {
        Consumer c = new Consumer();
        c.setNormalizedEmail("vol-" + UUID.randomUUID() + "@example.com");
        c = consumers.save(c);

        Membership m = new Membership();
        m.setOrgId(UUID.randomUUID());
        m.setConsumerId(c.getConsumerId());
        m.setStatus("subscribed");
        m = memberships.save(m);

        Campaign camp = new Campaign();
        camp.setId(UUID.randomUUID());
        camp.setOrgId(m.getOrgId());
        camp.setChannel("email");
        camp.setName("t");
        camp.setStatus("sent");
        camp.setCreatedAt(Instant.now());
        camp.setUpdatedAt(Instant.now());
        campaignRepo.save(camp);

        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(camp.getId());
        r.setMembershipId(m.getMembershipId());
        r.setEmail("m@example.com");
        r.setStatus("sent");
        r.setLastEventAt(sentAt);
        recipientRepo.save(r);
        return m.getMembershipId();
    }

    @Test
    void memberSentRecentlyIsFrequencyCapped() {
        UUID member = sentMembership(Instant.now().minus(2, ChronoUnit.HOURS));
        assertThat(guard.isFrequencyCapped(member, Instant.now())).isTrue();
    }

    @Test
    void memberNotSentWithinWindowIsNotCapped() {
        UUID member = sentMembership(Instant.now().minus(10, ChronoUnit.DAYS));
        assertThat(guard.isFrequencyCapped(member, Instant.now())).isFalse();
    }
}

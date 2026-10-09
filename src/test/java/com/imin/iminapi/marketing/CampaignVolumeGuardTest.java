package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignVolumeGuard;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IminIntegrationTest
class CampaignVolumeGuardTest {

    @Autowired CampaignVolumeGuard guard;
    @Autowired CampaignRecipientRepository recipientRepo;
    @Autowired CampaignRepository campaignRepo;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired IminFixtures fx;

    /** campaign_recipients references a real campaign and membership (V53); returns the membership the guard reads. */
    private UUID sentMembership(Instant sentAt) {
        Consumer c = new Consumer();
        c.setNormalizedEmail(fx.email("vol"));
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
        r.setEmail(c.getNormalizedEmail());
        r.setStatus("sent");
        r.setLastEventAt(sentAt);
        recipientRepo.save(r);
        return m.getMembershipId();
    }

    @Test
    void memberSentRecentlyIsFrequencyCapped() {
        UUID member = sentMembership(Instant.now().minus(2, ChronoUnit.HOURS));
        assertThat(guard.frequencyCapped(List.of(member), Instant.now())).containsExactly(member);
    }

    @Test
    void memberNotSentWithinWindowIsNotCapped() {
        UUID member = sentMembership(Instant.now().minus(10, ChronoUnit.DAYS));
        assertThat(guard.frequencyCapped(List.of(member), Instant.now())).isEmpty();
    }

    @Test
    void aRecentlySentMemberPastTheFirstQueryChunkIsStillCapped() {
        UUID member = sentMembership(Instant.now().minus(2, ChronoUnit.HOURS));
        List<UUID> ids = new ArrayList<>();
        // The guard queries 1,000 ids at a time; the sent member is the 1,001st.
        for (int i = 0; i < 1000; i++) ids.add(UUID.randomUUID());
        ids.add(member);

        assertThat(guard.frequencyCapped(ids, Instant.now())).containsExactly(member);
    }
}

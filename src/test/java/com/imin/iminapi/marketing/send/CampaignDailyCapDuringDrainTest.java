package com.imin.iminapi.marketing.send;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * mkt-core-5 (P2): the per-org daily cap was checked once, at claim time, and a campaign
 * admitted below the cap then drained without limit — a 200k-recipient campaign sent 200k
 * mails against a 10,000/day cap on a SHARED sending domain. The cap has to be re-checked
 * per batch, and the campaign left reclaimable when it bites.
 */
@SpringBootTest(properties = "imin.marketing.guard.daily-cap=100")
@Import(TestRateLimitConfig.class)
class CampaignDailyCapDuringDrainTest {

    @Autowired CampaignDispatcher dispatcher;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired OrganizationRepository orgs;
    @MockitoBean CampaignEmailProvider provider;

    private Organization awakeOrg() {
        int hourNowUtc = Instant.now().atZone(ZoneOffset.UTC).getHour();
        Organization o = new Organization();
        o.setName("Cap Org");
        o.setSlug("cap-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("cap@test.com");
        o.setCountry("DE");
        o.setTimezone(ZoneOffset.ofHours(12 - hourNowUtc).getId());
        return orgs.save(o);
    }

    @Test
    void drainStopsAtTheDailyCapAndLeavesTheRestQueued() {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(awakeOrg().getId());
        c.setChannel("email");
        c.setName("Over cap");
        c.setStatus("scheduled");
        c.setScheduledAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        c.setSubject("Subject");
        c.setBodyMd("Body");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaigns.save(c);
        for (int i = 0; i < 150; i++) {   // cap is 100: batch 1 fills it, batch 2 must not run
            CampaignRecipient r = new CampaignRecipient();
            r.setId(UUID.randomUUID());
            r.setCampaignId(c.getId());
            r.setMembershipId(null);
            r.setEmail("cap-" + UUID.randomUUID() + "@example.com");
            r.setStatus("pending");
            recipients.save(r);
        }

        when(provider.sendBatch(anyList())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(0)).stream().map(x -> "msg-" + UUID.randomUUID()).toList());

        dispatcher.runOnce();

        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "sent")).isEqualTo(100L);
        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "pending")).isEqualTo(50L);
        // Held, not finished: the campaign must stay reclaimable once the window rolls forward.
        Campaign after = campaigns.findByIdAndOrgId(c.getId(), c.getOrgId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("sending");
        assertThat(after.getSentAt()).isNull();
    }
}

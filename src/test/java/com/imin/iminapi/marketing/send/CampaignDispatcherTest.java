package com.imin.iminapi.marketing.send;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class CampaignDispatcherTest {

    @Autowired CampaignDispatcher dispatcher;
    @Autowired CampaignRepository campaigns;
    @Autowired OrganizationRepository orgs;
    @MockitoBean RecipientMaterializer materializer;
    @MockitoBean EmailChannelSender sender;

    /**
     * A real, non-paused org whose timezone puts local time near noon RIGHT NOW, so the
     * dispatcher's quiet-hours gate (22:00–09:00 org-local) never drops these campaigns
     * regardless of when the suite runs. The dispatcher now resolves the org per campaign,
     * so a random non-existent org_id would be skipped as "org gone".
     */
    private Organization awakeOrg() {
        int hourNowUtc = Instant.now().atZone(ZoneOffset.UTC).getHour();
        // offset that shifts current UTC hour to ~12:00 local, clamped to valid ±18h range
        int offset = 12 - hourNowUtc;
        Organization o = new Organization();
        o.setName("Disp Org");
        o.setSlug("disp-" + UUID.randomUUID().toString().substring(0, 6));
        o.setContactEmail("disp@test.com");
        o.setCountry("DE");
        o.setTimezone(ZoneOffset.ofHours(offset).getId()); // e.g. "+03:00"
        return orgs.save(o);
    }

    private Campaign scheduled(Instant at) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(awakeOrg().getId());
        c.setChannel("email");
        c.setName("Due");
        c.setStatus("scheduled");
        c.setScheduledAt(at);
        c.setSubject("S");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    @Test
    void claimsDueScheduledCampaignAndDrivesToSent() {
        Campaign c = scheduled(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(sender.sendNextBatch(any())).thenReturn(false); // no more pending → done

        dispatcher.runOnce();

        Campaign after = campaigns.findByIdAndOrgId(c.getId(), c.getOrgId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("sent");
        assertThat(after.getSentAt()).isNotNull();
    }

    @Test
    void ignoresFutureScheduledCampaign() {
        Campaign c = scheduled(Instant.now().plus(1, ChronoUnit.HOURS));
        dispatcher.runOnce();
        Campaign after = campaigns.findByIdAndOrgId(c.getId(), c.getOrgId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("scheduled");
    }

    @Test
    void midDriveCrash_doesNotCommitSentStatus() {
        Campaign c = scheduled(Instant.now().minus(1, ChronoUnit.MINUTES));
        // Sender throws after processOne has already set status→sending and saved.
        when(sender.sendNextBatch(any()))
                .thenThrow(new RuntimeException("provider exploded mid-batch"));

        dispatcher.runOnce();

        Campaign after = campaigns.findByIdAndOrgId(c.getId(), c.getOrgId()).orElseThrow();
        // NOT 'sent' — the transactional unit rolled the status flip back, then markFailed ran.
        assertThat(after.getStatus()).isEqualTo("failed");
        assertThat(after.getAttempts()).isGreaterThanOrEqualTo((short) 1);
    }
}

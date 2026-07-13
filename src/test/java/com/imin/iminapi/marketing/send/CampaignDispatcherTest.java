package com.imin.iminapi.marketing.send;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
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
    @MockitoBean RecipientMaterializer materializer;
    @MockitoBean EmailChannelSender sender;

    private Campaign scheduled(Instant at) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(UUID.randomUUID());
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

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * mkt-core-1 (P0): a crash part-way through a campaign drive must not un-record the
 * emails that already left. Emails are irreversible one batch at a time, so every
 * batch's recipient statuses have to be committed before the next batch is claimed —
 * otherwise the dispatcher's automatic re-claim re-materialises and re-sends the whole
 * audience.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class CampaignSendCrashResumeTest {

    private static final int TOTAL = 150; // two batches: 100 + 50

    @Autowired CampaignDispatcher dispatcher;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired OrganizationRepository orgs;
    @MockitoBean CampaignEmailProvider provider;

    /** Org whose local time is ~noon right now, so quiet hours never gate the dispatcher. */
    private Organization awakeOrg() {
        int hourNowUtc = Instant.now().atZone(ZoneOffset.UTC).getHour();
        Organization o = new Organization();
        o.setName("Crash Org");
        o.setSlug("crash-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("crash@test.com");
        o.setCountry("DE");
        o.setTimezone(ZoneOffset.ofHours(12 - hourNowUtc).getId());
        return orgs.save(o);
    }

    private Campaign dueCampaignWith(int pending, String emailTag) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(awakeOrg().getId());
        c.setChannel("email");
        c.setName("Crash blast");
        c.setStatus("scheduled");
        c.setScheduledAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        c.setSubject("Subject");
        c.setBodyMd("Body");
        Instant now = Instant.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        campaigns.save(c);
        for (int i = 0; i < pending; i++) {
            CampaignRecipient r = new CampaignRecipient();
            r.setId(UUID.randomUUID());
            r.setCampaignId(c.getId());
            // membership_id stays null: H2 enforces the FK to memberships (see
            // EmailChannelSenderTest) and the sender only reads the email column.
            r.setMembershipId(null);
            r.setEmail(emailTag + "-" + i + "@example.com");
            r.setStatus("pending");
            recipients.save(r);
        }
        return c;
    }

    @Test
    void crashAfterFirstBatchDoesNotResendAlreadySentRecipients() {
        String tag = "crash" + UUID.randomUUID().toString().substring(0, 8);
        Campaign c = dueCampaignWith(TOTAL, tag);

        AtomicInteger calls = new AtomicInteger();
        List<String> addressed = Collections.synchronizedList(new ArrayList<>());
        when(provider.sendBatch(anyList())).thenAnswer(inv -> {
            List<CampaignEmailProvider.OutgoingEmail> batch = inv.getArgument(0);
            // Second call = the pod dies after batch 1's emails have irreversibly left.
            if (calls.incrementAndGet() == 2) throw new RuntimeException("pod restart mid-send");
            batch.forEach(e -> addressed.add(e.to()));
            return batch.stream().map(e -> "msg-" + UUID.randomUUID()).toList();
        });

        dispatcher.runOnce();

        // Batch 1 committed even though batch 2 blew the drive up.
        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "sent")).isEqualTo(100L);
        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "pending")).isEqualTo(50L);

        // The dispatcher re-claims the failed campaign on the next tick.
        dispatcher.runOnce();

        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "sent")).isEqualTo((long) TOTAL);
        List<String> mine = addressed.stream().filter(a -> a.startsWith(tag)).toList();
        assertThat(mine).hasSize(TOTAL).doesNotHaveDuplicates();
        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "sent"))
                .allSatisfy(r -> assertThat(r.getAttemptCount()).isEqualTo((short) 1));
    }
}

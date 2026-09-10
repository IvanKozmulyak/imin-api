package com.imin.iminapi.marketing.send;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * mkt-core-11 (P2): a failed batch left its rows 'pending' and sendNextBatch reported more
 * work, so the drain loop re-claimed the SAME 100 rows and re-POSTed the identical batch to
 * Resend within milliseconds — three times before attempt_count exhausted. Resend's batch
 * API is not all-or-nothing, so a partial outage became a triple send for the accepted part,
 * and a hard outage became a hot loop against a provider that was already down.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class EmailChannelSenderBackoffTest {

    @Autowired EmailChannelSender sender;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @MockitoBean CampaignEmailProvider provider;

    private Campaign campaignWithPending(int n) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(UUID.randomUUID());
        c.setChannel("email");
        c.setName("Backoff blast");
        c.setStatus("sending");
        c.setSubject("Subject");
        c.setBodyMd("Body");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaigns.save(c);
        for (int i = 0; i < n; i++) {
            CampaignRecipient r = new CampaignRecipient();
            r.setId(UUID.randomUUID());
            r.setCampaignId(c.getId());
            r.setMembershipId(null);
            r.setEmail("backoff-" + UUID.randomUUID() + "@example.com");
            r.setStatus("pending");
            recipients.save(r);
        }
        return c;
    }

    @Test
    void aFailedBatchIsNotRePostedImmediately() {
        Campaign c = campaignWithPending(2);
        when(provider.sendBatch(anyList())).thenThrow(new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE, "down"));

        boolean more = sender.sendNextBatch(c);
        assertThat(more).isFalse();   // the drive stops instead of spinning on a dead provider

        // The rows are still queued, but not claimable until their backoff elapses.
        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "pending"))
                .hasSize(2)
                .allSatisfy(r -> {
                    assertThat(r.getAttemptCount()).isEqualTo((short) 1);
                    assertThat(r.getNextAttemptAt()).isAfter(Instant.now());
                });

        sender.sendNextBatch(c);
        verify(provider, times(1)).sendBatch(anyList());
    }

    /**
     * mkt-edge-4 (P1): a 4xx from Resend fails identically on every retry, so backing the rows
     * off three times only delays the truth and keeps the campaign 'sending'. Mark them failed
     * with the reason on the row — /retry (which requeues 'failed') is the recovery path.
     */
    @Test
    void aTerminalProviderRejectionFailsTheRowsInsteadOfBackingThemOff() {
        Campaign c = campaignWithPending(2);
        when(provider.sendBatch(anyList())).thenThrow(
                new CampaignEmailProvider.TerminalBatchFailure("Invalid `to` field", 422, null));

        boolean more = sender.sendNextBatch(c);
        assertThat(more).isFalse();

        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "pending")).isEmpty();
        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "failed"))
                .hasSize(2)
                .allSatisfy(r -> {
                    assertThat(r.getErrorCode()).isEqualTo("provider_rejected");
                    assertThat(r.getAttemptCount()).isEqualTo((short) 1);
                });
    }

    /**
     * mkt-edge-4: DSAR erasure nulls campaign_recipients.email for every row of an erased
     * membership, 'pending' rows included, and the sender then built an OutgoingEmail with a
     * null address. resend-java wraps a null `to` into a one-null list rather than throwing,
     * so the failure happened on the wire and took the whole batch (and the campaign) with it.
     * An address-less row is never sendable: divert it before the batch is assembled.
     */
    @Test
    void aRowWithNoAddressIsSkippedNotSent() {
        Campaign c = campaignWithPending(1);
        CampaignRecipient orphan = new CampaignRecipient();
        orphan.setId(UUID.randomUUID());
        orphan.setCampaignId(c.getId());
        orphan.setMembershipId(null);
        orphan.setEmail(null);              // DSAR-redacted while still pending
        orphan.setStatus("pending");
        recipients.save(orphan);

        when(provider.sendBatch(anyList())).thenReturn(java.util.List.of("id-a"));
        sender.sendNextBatch(c);

        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "skipped"))
                .hasSize(1)
                .allSatisfy(r -> assertThat(r.getSkipReason()).isEqualTo("no_email"));
        // Only the addressable row reached the provider.
        org.mockito.ArgumentCaptor<java.util.List<CampaignEmailProvider.OutgoingEmail>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(provider).sendBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).to()).isNotBlank();
    }
}

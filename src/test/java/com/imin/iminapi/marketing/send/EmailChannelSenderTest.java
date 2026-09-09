package com.imin.iminapi.marketing.send;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class EmailChannelSenderTest {

    @Autowired EmailChannelSender sender;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired MarketingEmailProperties marketingProps;
    @MockitoBean CampaignEmailProvider provider;

    private Campaign campaignWithPending(int n) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(UUID.randomUUID());
        c.setChannel("email");
        c.setName("Blast");
        c.setStatus("sending");
        c.setSubject("Subject");
        c.setBodyMd("Hello **there**");
        // Campaign has NO @PrePersist; created_at/updated_at are NOT NULL and are set by
        // callers (Phase-1 convention, mirrored in RecipientMaterializerTest).
        Instant now = Instant.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        campaigns.save(c);
        for (int i = 0; i < n; i++) {
            CampaignRecipient r = new CampaignRecipient();
            r.setId(UUID.randomUUID());
            r.setCampaignId(c.getId());
            // membership_id is UUID REFERENCES memberships(membership_id) ON DELETE SET NULL (V53).
            // H2 (MODE=PostgreSQL) ENFORCES this FK: a random membership_id with no memberships
            // row fails at flush with "Referential integrity constraint violation … FOREIGN
            // KEY(membership_id) REFERENCES public.memberships(membership_id) [23506-240]". The
            // FK permits NULL and the sender reads r.getEmail() only (never a real membership),
            // so leave membership_id null. The sender only SIGNS the unsubscribe token
            // (tokens.sign(orgId, null, campaignId, "email") → payload "orgId:null:campaignId:email",
            // signs without error since it never parses the UUID); it never verifies it during
            // send, so a null membershipId is safe for this test.
            r.setMembershipId(null);
            r.setEmail("r" + i + "@example.com");
            r.setStatus("pending");
            recipients.save(r);
        }
        return c;
    }

    @Test
    void sendsPendingRowsAndRecordsProviderIds() {
        Campaign c = campaignWithPending(2);
        when(provider.sendBatch(anyList())).thenReturn(List.of("id-a", "id-b"));

        boolean more = sender.sendNextBatch(c);

        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "sent")).isEqualTo(2L);
        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "sent"))
                .allSatisfy(r -> assertThat(r.getProviderMessageId()).isNotBlank());
        assertThat(more).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchSendRendersThroughTheBrandedShellNotBareText() {
        // Regression for the test-send bug's sibling path: the batch sender must render each
        // recipient's email through CampaignEmailRenderer (branded HTML shell + mandatory
        // unsubscribe footer), NOT ship raw markdown as text.
        Campaign c = campaignWithPending(1);
        when(provider.sendBatch(anyList())).thenReturn(List.of("id-a"));

        sender.sendNextBatch(c);

        ArgumentCaptor<List<CampaignEmailProvider.OutgoingEmail>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(provider).sendBatch(captor.capture());
        CampaignEmailProvider.OutgoingEmail sent = captor.getValue().get(0);
        assertThat(sent.html()).contains("<!DOCTYPE html>");
        assertThat(sent.html()).contains("<strong>there</strong>"); // markdown was rendered
        assertThat(sent.html().toLowerCase()).contains("unsubscribe");
    }

    /**
     * mkt-core-16 (P3): provider ids are matched to recipients purely by position, and
     * CampaignEmailProvider returns whatever Resend's data array held with no assertion that
     * it is the same length. A short list left the tail rows 'sent' with a null
     * provider_message_id, so findByProviderMessageId could never resolve their
     * delivery/bounce/complaint events — permanently invisible to the stats and to the
     * complaint breaker. The row must at least say so.
     */
    @Test
    void aShortProviderIdListMarksTheUntrackableTail() {
        Campaign c = campaignWithPending(2);
        when(provider.sendBatch(anyList())).thenReturn(List.of("id-only-one"));

        sender.sendNextBatch(c);

        List<CampaignRecipient> sent = recipients.findByCampaignIdAndStatus(c.getId(), "sent");
        assertThat(sent).hasSize(2);   // the mail left; we do not pretend otherwise
        assertThat(sent).anySatisfy(r -> {
            assertThat(r.getProviderMessageId()).isNull();
            assertThat(r.getErrorCode()).isEqualTo("no_provider_id");
        });
        assertThat(sent).anySatisfy(r -> {
            assertThat(r.getProviderMessageId()).isEqualTo("id-only-one");
            assertThat(r.getErrorCode()).isNull();
        });
    }

    @Test
    void providerFailureLeavesRowsPendingAndIncrementsAttempt() {
        Campaign c = campaignWithPending(1);
        when(provider.sendBatch(anyList())).thenThrow(
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE, "down"));

        sender.sendNextBatch(c);

        List<CampaignRecipient> pending = recipients.findByCampaignIdAndStatus(c.getId(), "pending");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getAttemptCount()).isEqualTo((short) 1);
    }
}

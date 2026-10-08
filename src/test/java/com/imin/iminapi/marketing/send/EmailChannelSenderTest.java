package com.imin.iminapi.marketing.send;

import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@IminIntegrationTest
class EmailChannelSenderTest {

    @Autowired EmailChannelSender sender;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired CampaignEmailProvider provider;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();
    private final Set<String> ownAddresses = new HashSet<>();

    /** The campaigns are left 'sending', which the global claim would reclaim once stale. */
    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    /** The one email this test's campaign handed to the provider. */
    @SuppressWarnings("unchecked")
    private CampaignEmailProvider.OutgoingEmail sentEmail() {
        ArgumentCaptor<List<CampaignEmailProvider.OutgoingEmail>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider, atLeast(0)).sendBatch(captor.capture());
        List<CampaignEmailProvider.OutgoingEmail> mine = captor.getAllValues().stream().flatMap(List::stream)
                .filter(e -> ownAddresses.contains(e.to())).toList();
        assertThat(mine).hasSize(1);
        return mine.get(0);
    }

    private Campaign campaignWithPending(int n) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(UUID.randomUUID());
        orgIds.add(c.getOrgId());
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
            // membership_id is an FK to memberships (V53) that permits NULL; the sender reads the email
            // column only and merely signs the unsubscribe token, so no membership is needed here.
            r.setMembershipId(null);
            r.setEmail(fx.email("r" + i));
            r.setStatus("pending");
            recipients.save(r);
            ownAddresses.add(r.getEmail());
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
    void batchSendRendersThroughTheBrandedShellNotBareText() {
        // Regression for the test-send bug's sibling path: the batch sender must render each
        // recipient's email through CampaignEmailRenderer (branded HTML shell + mandatory
        // unsubscribe footer), NOT ship raw markdown as text.
        Campaign c = campaignWithPending(1);
        when(provider.sendBatch(anyList())).thenReturn(List.of("id-a"));

        sender.sendNextBatch(c);

        CampaignEmailProvider.OutgoingEmail sent = sentEmail();
        assertThat(sent.html()).contains("<!DOCTYPE html>");
        assertThat(sent.html()).contains("<strong>there</strong>"); // markdown was rendered
        assertThat(sent.html().toLowerCase()).contains("unsubscribe");
    }

    @Test
    void aiGeneratedCampaign_handsTheDisclosureToTheProvider_andMarksTheHtml() {
        Campaign c = campaignWithPending(1);
        c.setSubjectAiGenerated(true);
        campaigns.save(c);
        when(provider.sendBatch(anyList())).thenReturn(List.of("id-a"));

        sender.sendNextBatch(c);

        CampaignEmailProvider.OutgoingEmail sent = sentEmail();
        assertThat(sent.ai().subject()).isTrue();
        assertThat(sent.ai().body()).isFalse();
        assertThat(sent.html()).contains("<meta name=\"imin-ai-generated\" content=\"subject\"/>");
    }

    @Test
    void humanWrittenCampaign_sendsNoDisclosure() {
        Campaign c = campaignWithPending(1);
        when(provider.sendBatch(anyList())).thenReturn(List.of("id-a"));

        sender.sendNextBatch(c);

        CampaignEmailProvider.OutgoingEmail sent = sentEmail();
        assertThat(sent.ai().any()).isFalse();
        assertThat(sent.html()).doesNotContain("ai-disclosure");
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

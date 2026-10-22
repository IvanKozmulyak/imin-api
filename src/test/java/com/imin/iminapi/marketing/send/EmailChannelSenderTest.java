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
import com.imin.iminapi.support.OrgRows;
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
import static org.mockito.ArgumentMatchers.any;
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
    @Autowired com.imin.iminapi.marketing.email.MarketingEmailProperties props;
    @Autowired com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService tokens;

    private final List<UUID> orgIds = new ArrayList<>();
    private final Set<String> ownAddresses = new HashSet<>();
    private final List<UUID> realOrgIds = new ArrayList<>();
    private final List<UUID> consumerIds = new ArrayList<>();

    /** The campaigns are left 'sending', which the global claim would reclaim once stale. */
    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
        OrgRows.delete(jdbc, realOrgIds);
        for (UUID id : consumerIds) {
            jdbc.update("delete from memberships where consumer_id = ?", id);
            jdbc.update("delete from consumers where consumer_id = ?", id);
        }
    }

    /** The one email this test's campaign handed to the provider. */
    @SuppressWarnings("unchecked")
    private CampaignEmailProvider.OutgoingEmail sentEmail() {
        ArgumentCaptor<List<CampaignEmailProvider.OutgoingEmail>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider, atLeast(0)).sendBatch(any(), captor.capture());
        List<CampaignEmailProvider.OutgoingEmail> mine = captor.getAllValues().stream().flatMap(List::stream)
                .filter(e -> ownAddresses.contains(e.to())).toList();
        assertThat(mine).hasSize(1);
        return mine.get(0);
    }

    private Campaign campaignWithPending(int n) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        // A real org: the per-batch send gate reads its pause.
        c.setOrgId(fx.org().getId());
        orgIds.add(c.getOrgId());
        realOrgIds.add(c.getOrgId());
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
        when(provider.sendBatch(any(), anyList())).thenReturn(CampaignEmailProvider.BatchResult.accepted(List.of("id-a", "id-b")));

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
        when(provider.sendBatch(any(), anyList())).thenReturn(CampaignEmailProvider.BatchResult.accepted(List.of("id-a")));

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
        when(provider.sendBatch(any(), anyList())).thenReturn(CampaignEmailProvider.BatchResult.accepted(List.of("id-a")));

        sender.sendNextBatch(c);

        CampaignEmailProvider.OutgoingEmail sent = sentEmail();
        assertThat(sent.ai().subject()).isTrue();
        assertThat(sent.ai().body()).isFalse();
        assertThat(sent.html()).contains("<meta name=\"imin-ai-generated\" content=\"subject\"/>");
    }

    @Test
    void humanWrittenCampaign_sendsNoDisclosure() {
        Campaign c = campaignWithPending(1);
        when(provider.sendBatch(any(), anyList())).thenReturn(CampaignEmailProvider.BatchResult.accepted(List.of("id-a")));

        sender.sendNextBatch(c);

        CampaignEmailProvider.OutgoingEmail sent = sentEmail();
        assertThat(sent.ai().any()).isFalse();
        assertThat(sent.html()).doesNotContain("ai-disclosure");
    }

    /**
     * A provider answer that does not account for a row says nothing about whether it went out: that row is neither
     * counted as sent (stats, daily cap) nor mailed again, but skipped as delivery_unconfirmed.
     */
    @Test
    void aShortProviderIdListLeavesTheTailUnconfirmed() {
        Campaign c = campaignWithPending(2);
        when(provider.sendBatch(any(), anyList())).thenReturn(CampaignEmailProvider.BatchResult.accepted(List.of("id-only-one")));

        sender.sendNextBatch(c);

        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "sent")).singleElement().satisfies(r -> {
            assertThat(r.getProviderMessageId()).isEqualTo("id-only-one");
            assertThat(r.getErrorCode()).isNull();
        });
        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "skipped")).singleElement().satisfies(r -> {
            assertThat(r.getSkipReason()).isEqualTo("delivery_unconfirmed");
            assertThat(r.getSentAt()).isNull();
            assertThat(r.getProviderMessageId()).isNull();
        });
        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "pending")).isZero();
    }

    @Test
    void providerFailureLeavesRowsPendingAndIncrementsAttempt() {
        Campaign c = campaignWithPending(1);
        when(provider.sendBatch(any(), anyList())).thenThrow(
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE, "down"));

        sender.sendNextBatch(c);

        List<CampaignRecipient> pending = recipients.findByCampaignIdAndStatus(c.getId(), "pending");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getAttemptCount()).isEqualTo((short) 1);
    }

    @Test
    void frenchCampaignGoesOutInFrenchWithTheFooterOnTheBuyerPage() {
        Campaign c = campaignWithPending(1);
        // A real org and member, so the token can be verified back to this recipient.
        UUID orgId = fx.org().getId();
        realOrgIds.add(orgId);
        orgIds.add(orgId);
        c.setOrgId(orgId);
        UUID consumer = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        consumerIds.add(consumer);
        jdbc.update("insert into consumers (consumer_id, normalized_email) values (?, ?)", consumer, fx.email("member"));
        jdbc.update("insert into memberships (membership_id, org_id, consumer_id, status, consent_status, consent_basis)"
                + " values (?, ?, ?, 'active', 'subscribed', 'explicit')", member, orgId, consumer);
        jdbc.update("update campaign_recipients set membership_id = ? where campaign_id = ?", member, c.getId());
        c.setLocale("fr");
        c.setBodyMd("Bonjour {{firstName}},\n\nVenez nombreux.");
        campaigns.save(c);
        when(provider.sendBatch(any(), anyList())).thenReturn(CampaignEmailProvider.BatchResult.accepted(List.of("id-a")));

        sender.sendNextBatch(c);

        CampaignEmailProvider.OutgoingEmail sent = sentEmail();
        String token = sent.unsubscribeUrl().substring(sent.unsubscribeUrl().lastIndexOf('/') + 1);
        assertThat(sent.html()).contains("<html lang=\"fr\">");
        assertThat(sent.html()).contains("Se désinscrire").contains("Vous recevez cet e-mail");
        // A fan with no name is greeted without one, never "Bonjour there,".
        assertThat(sent.html()).contains("<p>Bonjour,</p>").doesNotContain("Bonjour there");
        // The footer opens the buyer page; the one-tap header posts to the API, with the same token.
        assertThat(sent.html()).contains("href=\"" + props.unsubscribePageUrl(token) + "\"");
        assertThat(sent.unsubscribeUrl()).isEqualTo(props.unsubscribeUrl(token));
        assertThat(tokens.verify(token)).hasValueSatisfying(claims -> {
            assertThat(claims.membershipId()).isEqualTo(member);
            assertThat(claims.campaignId()).isEqualTo(c.getId());
        });
        assertThat(sent.html()).doesNotContain("href=\"" + props.unsubscribeUrl(token) + "\"");
    }
}

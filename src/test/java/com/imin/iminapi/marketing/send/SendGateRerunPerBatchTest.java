package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * mkt-core-4 (P2): the Send Gate is frozen at materialisation, and a large campaign drains
 * for minutes-to-hours afterwards. Somebody who unsubscribes (or is suppressed by a
 * complaint/hard-bounce webhook) during that window was still emailed, because the pending
 * row was re-read but consent never was. The gate has to run again for each claimed batch.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class SendGateRerunPerBatchTest {

    @Autowired EmailChannelSender sender;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @MockitoBean CampaignEmailProvider provider;

    private record Member(Membership membership, String email) {}

    private Member member(UUID orgId, String consentStatus, String consentBasis) {
        Consumer cn = new Consumer();
        String email = "gate-" + UUID.randomUUID() + "@example.com";
        cn.setNormalizedEmail(email);
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setStatus("active");
        m.setConsentStatus(consentStatus);
        m.setConsentBasis(consentBasis);
        return new Member(memberships.save(m), email);
    }

    private CampaignRecipient pendingRow(Campaign c, Member m) {
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(m.membership().getMembershipId());
        r.setEmail(m.email());
        r.setStatus("pending");
        return recipients.save(r);
    }

    @Test
    void recipientWhoUnsubscribedAfterMaterialisationIsSkippedNotSent() {
        UUID orgId = UUID.randomUUID();
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Gate blast");
        c.setStatus("sending");
        c.setSubject("Subject");
        c.setBodyMd("Body");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaigns.save(c);

        Member stillGood = member(orgId, "subscribed", "consent");
        // Materialisation snapshotted this member as sendable; they opted out afterwards.
        Member optedOut = member(orgId, "unsubscribed", "consent");
        CampaignRecipient goodRow = pendingRow(c, stillGood);
        CampaignRecipient optedOutRow = pendingRow(c, optedOut);

        when(provider.sendBatch(anyList())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(0)).stream().map(x -> "msg-" + UUID.randomUUID()).toList());

        sender.sendNextBatch(c);

        assertThat(recipients.findById(goodRow.getId()).orElseThrow().getStatus()).isEqualTo("sent");
        CampaignRecipient skipped = recipients.findById(optedOutRow.getId()).orElseThrow();
        assertThat(skipped.getStatus()).isEqualTo("skipped");
        assertThat(skipped.getSkipReason()).isEqualTo("marketing_unsubscribed");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CampaignEmailProvider.OutgoingEmail>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(provider).sendBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).to()).isEqualTo(goodRow.getEmail());
    }
}

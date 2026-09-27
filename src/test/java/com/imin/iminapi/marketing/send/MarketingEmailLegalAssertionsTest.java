package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.resend.Resend;
import com.resend.services.batch.Batch;
import com.resend.services.batch.model.BatchEmail;
import com.resend.services.batch.model.CreateBatchEmailsResponse;
import com.resend.services.emails.model.CreateEmailOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What leaves for Resend on the real send path: no per-recipient link (tracking is off, UTM is
 * campaign-level), and every email carries the unsubscribe link and both RFC 8058 headers.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class MarketingEmailLegalAssertionsTest {

    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");

    @Autowired EmailChannelSender sender;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @MockitoBean Resend resend;

    private UUID member(UUID orgId, String email, String name) {
        Consumer cn = new Consumer();
        cn.setNormalizedEmail(email);
        cn.setDisplayName(name);
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setStatus("active");
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        return memberships.save(m).getMembershipId();
    }

    @SuppressWarnings("unchecked")
    private List<CreateEmailOptions> sendToTwo() throws Exception {
        Batch batch = mock(Batch.class);
        when(resend.batch()).thenReturn(batch);
        when(batch.send(anyList())).thenReturn(new CreateBatchEmailsResponse(
                List.of(new BatchEmail("msg-1"), new BatchEmail("msg-2"))));

        UUID orgId = UUID.randomUUID();
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Legal");
        c.setStatus("sending");
        c.setSubject("Hi {{firstName}}");
        c.setBodyMd("Hi {{firstName}}, [Tickets](https://imin.wtf/e/night) and [Venue](https://example.com/venue)");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaigns.save(c);
        String[][] people = {{"ana-" + UUID.randomUUID() + "@example.com", "Ana"},
                {"bo-" + UUID.randomUUID() + "@example.com", "Bo"}};
        for (String[] p : people) {
            CampaignRecipient r = new CampaignRecipient();
            r.setId(UUID.randomUUID());
            r.setCampaignId(c.getId());
            r.setMembershipId(member(orgId, p[0], p[1]));
            r.setEmail(p[0]);
            r.setStatus("pending");
            recipients.save(r);
        }

        sender.sendNextBatch(c);

        ArgumentCaptor<List<CreateEmailOptions>> captor = ArgumentCaptor.forClass(List.class);
        verify(batch).send(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        return captor.getValue();
    }

    private static String unsubscribeUrl(CreateEmailOptions o) {
        String h = o.getHeaders().get("List-Unsubscribe");
        return h.substring(1, h.length() - 1);
    }

    private static List<String> linksOtherThanUnsubscribe(CreateEmailOptions o) {
        String unsub = unsubscribeUrl(o);
        List<String> out = new ArrayList<>();
        Matcher m = HREF.matcher(o.getHtml());
        while (m.find()) {
            if (!m.group(1).equals(unsub)) out.add(m.group(1));
        }
        return out;
    }

    @Test
    void linksAreIdenticalForTwoRecipients_campaignLevelUtmOnly() throws Exception {
        List<CreateEmailOptions> sent = sendToTwo();
        CreateEmailOptions a = sent.get(0);
        CreateEmailOptions b = sent.get(1);

        assertThat(a.getHtml()).isNotEqualTo(b.getHtml());
        List<String> linksA = linksOtherThanUnsubscribe(a);
        assertThat(linksA).isNotEmpty().isEqualTo(linksOtherThanUnsubscribe(b));
        assertThat(linksA).anyMatch(l -> l.startsWith("https://imin.wtf/e/night?utm_source=imin&utm_medium=email&utm_campaign="));
        assertThat(linksA).contains("https://example.com/venue");
        for (String link : linksA) {
            assertThat(link).doesNotContain(a.getTo().get(0)).doesNotContain(b.getTo().get(0));
        }
    }

    @Test
    void everyEmailCarriesTheUnsubscribeLinkAndBothOneClickHeaders() throws Exception {
        List<CreateEmailOptions> sent = sendToTwo();

        assertThat(unsubscribeUrl(sent.get(0))).isNotEqualTo(unsubscribeUrl(sent.get(1)));
        for (CreateEmailOptions o : sent) {
            String unsub = unsubscribeUrl(o);
            assertThat(unsub).contains("/api/v1/public/unsubscribe/");
            assertThat(o.getHeaders()).containsEntry("List-Unsubscribe", "<" + unsub + ">")
                    .containsEntry("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
            assertThat(o.getHtml()).contains("href=\"" + unsub + "\"").contains(">Unsubscribe</a>");
            assertThat(o.getText()).endsWith("Unsubscribe: " + unsub);
        }
    }
}

package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The real send path hands the provider no per-recipient link (UTM is campaign-level) and a per-recipient
 * unsubscribe link; the RFC 8058 headers built from it are {@code CampaignEmailProviderTest}'s.
 */
@IminIntegrationTest
class MarketingEmailLegalAssertionsTest {

    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");

    @Autowired EmailChannelSender sender;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired CampaignEmailProvider provider;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();

    /** The campaign is left 'sending', which the global claim would reclaim once stale. */
    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

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
    private List<CampaignEmailProvider.OutgoingEmail> sendToTwo() {
        when(provider.sendBatch(anyList())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(0)).stream().map(x -> "msg-" + UUID.randomUUID()).toList());

        UUID orgId = UUID.randomUUID();
        orgIds.add(orgId);
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
        List<String> addresses = List.of(fx.email("ana"), fx.email("bo"));
        String[] names = {"Ana", "Bo"};
        for (int i = 0; i < addresses.size(); i++) {
            CampaignRecipient r = new CampaignRecipient();
            r.setId(UUID.randomUUID());
            r.setCampaignId(c.getId());
            r.setMembershipId(member(orgId, addresses.get(i), names[i]));
            r.setEmail(addresses.get(i));
            r.setStatus("pending");
            recipients.save(r);
        }

        sender.sendNextBatch(c);

        ArgumentCaptor<List<CampaignEmailProvider.OutgoingEmail>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider, atLeast(0)).sendBatch(captor.capture());
        List<CampaignEmailProvider.OutgoingEmail> mine = captor.getAllValues().stream().flatMap(List::stream)
                .filter(e -> addresses.contains(e.to())).toList();
        assertThat(mine).hasSize(2);
        return mine;
    }

    private static List<String> linksOtherThanUnsubscribe(CampaignEmailProvider.OutgoingEmail e) {
        List<String> out = new ArrayList<>();
        Matcher m = HREF.matcher(e.html());
        while (m.find()) {
            if (!m.group(1).equals(e.unsubscribeUrl())) out.add(m.group(1));
        }
        return out;
    }

    @Test
    void linksAreIdenticalForTwoRecipients_campaignLevelUtmOnly() {
        List<CampaignEmailProvider.OutgoingEmail> sent = sendToTwo();
        CampaignEmailProvider.OutgoingEmail a = sent.get(0);
        CampaignEmailProvider.OutgoingEmail b = sent.get(1);

        assertThat(a.html()).isNotEqualTo(b.html());
        List<String> linksA = linksOtherThanUnsubscribe(a);
        assertThat(linksA).isNotEmpty().isEqualTo(linksOtherThanUnsubscribe(b));
        assertThat(linksA).anyMatch(l -> l.startsWith("https://imin.wtf/e/night?utm_source=imin&utm_medium=email&utm_campaign="));
        assertThat(linksA).contains("https://example.com/venue");
        for (String link : linksA) {
            assertThat(link).doesNotContain(a.to()).doesNotContain(b.to());
        }
    }

    @Test
    void everyEmailCarriesItsOwnUnsubscribeLinkInHtmlAndText() {
        List<CampaignEmailProvider.OutgoingEmail> sent = sendToTwo();

        assertThat(sent.get(0).unsubscribeUrl()).isNotEqualTo(sent.get(1).unsubscribeUrl());
        for (CampaignEmailProvider.OutgoingEmail e : sent) {
            String unsub = e.unsubscribeUrl();
            assertThat(unsub).contains("/api/v1/public/unsubscribe/");
            assertThat(e.html()).contains("href=\"" + unsub + "\"").contains(">Unsubscribe</a>");
            assertThat(e.text()).endsWith("Unsubscribe: " + unsub);
        }
    }
}

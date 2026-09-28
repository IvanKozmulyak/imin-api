package com.imin.iminapi.marketing.send;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The per-organizer From and footer on the real batch path, and the audience-plan legal-identity stop. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class EmailChannelSenderIdentityTest {

    @Autowired EmailChannelSender sender;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @MockitoSpyBean OrganizationRepository orgs;
    @Autowired MarketingEmailProperties marketingProps;
    @Autowired com.imin.iminapi.audienceplan.config.AudiencePlanProperties planProps;
    @MockitoBean CampaignEmailProvider provider;

    private String savedFromAddress;
    private String savedFromName;

    @BeforeEach
    void configureSender() {
        savedFromAddress = marketingProps.getFromAddress();
        savedFromName = marketingProps.getFromName();
        marketingProps.setFromAddress("contact@imin.support");
        marketingProps.setFromName("Alex");
    }

    @AfterEach
    void restoreSender() {
        marketingProps.setFromAddress(savedFromAddress);
        marketingProps.setFromName(savedFromName);
        planProps.setLegalIdentityAllCampaigns(false);
    }

    private Organization org(String brand, String legalName, String legalContact) {
        Organization o = new Organization();
        o.setName("Night Org");
        o.setBrandName(brand);
        o.setSlug("id-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("ops@night.test");
        o.setCountry("FR");
        o.setLegalName(legalName);
        o.setLegalContact(legalContact);
        return orgs.save(o);
    }

    private Campaign campaignWithPending(Organization org, String origin) {
        return campaignWithPending(org.getId(), origin);
    }

    private Campaign campaignWithPending(UUID orgId, String origin) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Blast");
        c.setStatus("sending");
        c.setOrigin(origin);
        c.setSubject("Subject");
        c.setBodyMd("Hello");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaigns.save(c);
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(null);
        r.setEmail("fan@example.com");
        r.setStatus("pending");
        recipients.save(r);
        return c;
    }

    @SuppressWarnings("unchecked")
    private CampaignEmailProvider.OutgoingEmail sentEmail() {
        ArgumentCaptor<List<CampaignEmailProvider.OutgoingEmail>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider).sendBatch(captor.capture());
        return captor.getValue().get(0);
    }

    @Test
    void manualCampaign_isFromBrandViaImin_withTheLegalFooter() {
        Campaign c = campaignWithPending(org("Night", "Night SAS", "legal@night.test"), "manual");
        when(provider.sendBatch(anyList())).thenReturn(List.of("id-a"));

        sender.sendNextBatch(c);

        CampaignEmailProvider.OutgoingEmail e = sentEmail();
        assertThat(e.from()).isEqualTo("\"Night via IMIN\" <contact@imin.support>");
        assertThat(e.html()).contains("Night &middot; Night SAS &middot; legal@night.test");
        assertThat(e.text()).contains("Night · Night SAS · legal@night.test\nUnsubscribe: ");
    }

    @Test
    void orgWithoutBrand_isFromOrgNameViaImin() {
        Campaign c = campaignWithPending(org(null, null, null), "manual");
        when(provider.sendBatch(anyList())).thenReturn(List.of("id-a"));

        sender.sendNextBatch(c);

        assertThat(sentEmail().from()).isEqualTo("\"Night Org via IMIN\" <contact@imin.support>");
    }

    @Test
    void manualCampaign_withoutLegalIdentity_stillSends() {
        Campaign c = campaignWithPending(org(null, null, null), "manual");
        when(provider.sendBatch(anyList())).thenReturn(List.of("id-a"));

        sender.sendNextBatch(c);

        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "sent")).isEqualTo(1L);
        assertThat(sentEmail().html()).contains(">Night Org</div>");
    }

    private void assertStoppedForMissingIdentity(Campaign c, boolean more) {
        assertThat(more).isFalse();
        verify(provider, never()).sendBatch(anyList());
        Campaign after = campaigns.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("failed");
        assertThat(after.getLastError()).isEqualTo("ORG_LEGAL_IDENTITY_MISSING");
        assertThat(after.getAttempts()).isEqualTo((short) 1);
        assertThat(c.getStatus()).isEqualTo("failed");
        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "pending")).isEqualTo(1L);
        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "pending"))
                .allSatisfy(r -> assertThat(r.getAttemptCount()).isZero());
    }

    @Test
    void audiencePlanCampaign_legalNameWithoutContact_failsWithRowsPending() {
        Campaign c = campaignWithPending(org("Night", "Night SAS", null), "audience_plan");

        assertStoppedForMissingIdentity(c, sender.sendNextBatch(c));
    }

    @Test
    void audiencePlanCampaign_contactWithoutLegalName_failsWithRowsPending() {
        Campaign c = campaignWithPending(org("Night", null, "legal@night.test"), "audience_plan");

        assertStoppedForMissingIdentity(c, sender.sendNextBatch(c));
    }

    @Test
    void audiencePlanCampaign_orgMissing_failsWithRowsPending() {
        Campaign c = campaignWithPending(UUID.randomUUID(), "audience_plan");

        assertStoppedForMissingIdentity(c, sender.sendNextBatch(c));
    }

    @Test
    void audiencePlanCampaign_orgLookupFails_failsWithRowsPending() {
        Organization o = org("Night", "Night SAS", "legal@night.test");
        Campaign c = campaignWithPending(o, "audience_plan");
        doThrow(new IllegalStateException("db hiccup")).when(orgs).findById(o.getId());

        assertStoppedForMissingIdentity(c, sender.sendNextBatch(c));
    }

    @Test
    void manualCampaign_orgLookupFails_stillSendsFromTheConfiguredHeader() {
        Organization o = org("Night", null, null);
        Campaign c = campaignWithPending(o, "manual");
        doThrow(new IllegalStateException("db hiccup")).when(orgs).findById(o.getId());
        when(provider.sendBatch(anyList())).thenReturn(List.of("id-a"));

        sender.sendNextBatch(c);

        assertThat(sentEmail().from()).isEqualTo("Alex <contact@imin.support>");
    }

    @Test
    void audiencePlanCampaign_withLegalIdentity_sends() {
        Campaign c = campaignWithPending(org("Night", "Night SAS", "legal@night.test"), "audience_plan");
        when(provider.sendBatch(anyList())).thenReturn(List.of("id-a"));

        sender.sendNextBatch(c);

        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "sent")).isEqualTo(1L);
    }

    @Test
    void allCampaignsFlag_manualCampaignWithoutLegalContact_failsWithRowsPending() {
        planProps.setLegalIdentityAllCampaigns(true);
        Campaign c = campaignWithPending(org("Night", "Night SAS", null), "manual");

        assertStoppedForMissingIdentity(c, sender.sendNextBatch(c));
    }

    @Test
    void allCampaignsFlag_momentumCampaignWithLegalIdentity_sends() {
        planProps.setLegalIdentityAllCampaigns(true);
        Campaign c = campaignWithPending(org("Night", "Night SAS", "legal@night.test"), "momentum");
        when(provider.sendBatch(anyList())).thenReturn(List.of("id-a"));

        sender.sendNextBatch(c);

        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "sent")).isEqualTo(1L);
    }
}

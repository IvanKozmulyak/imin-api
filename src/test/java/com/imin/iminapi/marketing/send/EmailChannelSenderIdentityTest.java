package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * The per-organizer From and footer on the real batch path, and the audience-plan legal-identity stop.
 * A failing org lookup is {@link EmailChannelSenderOrgLookupTest}.
 */
@IminIntegrationTest
class EmailChannelSenderIdentityTest {

    @Autowired EmailChannelSender sender;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired OrganizationRepository orgs;
    @Autowired MarketingEmailProperties marketingProps;
    @Autowired AudiencePlanProperties planProps;
    @Autowired CampaignEmailProvider provider;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();
    private final Set<String> ownAddresses = new HashSet<>();

    @BeforeEach
    void configureSender() {
        flips.set(marketingProps, "fromAddress", "contact@imin.support");
        flips.set(marketingProps, "fromName", "Alex");
    }

    /** The campaigns are left 'sending' or 'failed', which the global claim would pick up. */
    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    private Organization org(String brand, String legalName, String legalContact) {
        Organization o = fx.org();
        o.setName("Night Org");
        o.setBrandName(brand);
        o.setCountry("FR");
        o.setLegalName(legalName);
        o.setLegalContact(legalContact);
        return orgs.save(o);
    }

    private Campaign campaignWithPending(Organization org, String origin) {
        return campaignWithPending(org.getId(), origin);
    }

    private Campaign campaignWithPending(UUID orgId, String origin) {
        orgIds.add(orgId);
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
        r.setEmail(fx.email("fan"));
        r.setStatus("pending");
        recipients.save(r);
        ownAddresses.add(r.getEmail());
        return c;
    }

    /** The emails this test's campaigns handed to the provider. */
    @SuppressWarnings("unchecked")
    private List<CampaignEmailProvider.OutgoingEmail> ownEmails() {
        ArgumentCaptor<List<CampaignEmailProvider.OutgoingEmail>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider, atLeast(0)).sendBatch(captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream)
                .filter(e -> ownAddresses.contains(e.to())).toList();
    }

    private CampaignEmailProvider.OutgoingEmail sentEmail() {
        List<CampaignEmailProvider.OutgoingEmail> mine = ownEmails();
        assertThat(mine).hasSize(1);
        return mine.get(0);
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
        assertThat(ownEmails()).isEmpty();
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
        // campaigns.org_id has no FK to organizations, so a campaign can outlive its org.
        Campaign c = campaignWithPending(UUID.randomUUID(), "audience_plan");

        assertStoppedForMissingIdentity(c, sender.sendNextBatch(c));
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
        flips.set(planProps, "legalIdentityAllCampaigns", true);
        Campaign c = campaignWithPending(org("Night", "Night SAS", null), "manual");

        assertStoppedForMissingIdentity(c, sender.sendNextBatch(c));
    }

    @Test
    void allCampaignsFlag_momentumCampaignWithLegalIdentity_sends() {
        flips.set(planProps, "legalIdentityAllCampaigns", true);
        Campaign c = campaignWithPending(org("Night", "Night SAS", "legal@night.test"), "momentum");
        when(provider.sendBatch(anyList())).thenReturn(List.of("id-a"));

        sender.sendNextBatch(c);

        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "sent")).isEqualTo(1L);
    }
}

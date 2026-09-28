package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** An audience-plan campaign whose org lacks a legal identity never loops and never starves other orgs. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AudiencePlanLegalIdentityDispatchTest {

    @Autowired CampaignDispatcher dispatcher;
    @Autowired CampaignSendUnit sendUnit;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired OrganizationRepository orgs;
    @Autowired AudiencePlanProperties props;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CampaignEmailProvider provider;

    @BeforeEach
    void setUp() {
        // The claim query is global with a LIMIT: start from no campaigns so membership is deterministic.
        jdbc.update("delete from campaign_recipients");
        jdbc.update("delete from campaigns");
        props.setSendsEnabled(true);
        when(provider.sendBatch(anyList())).thenAnswer(inv -> {
            List<?> batch = inv.getArgument(0);
            return batch.stream().map(e -> "id-" + UUID.randomUUID()).toList();
        });
    }

    @AfterEach
    void restore() {
        props.setSendsEnabled(false);
        props.setLegalIdentityAllCampaigns(false);
        // Leave nothing claimable: the claim is global, so a leftover would be sent by another class's dispatcher test.
        jdbc.update("delete from campaign_recipients");
        jdbc.update("delete from campaigns");
    }

    /** Local time near noon right now, so quiet hours never drop these campaigns. */
    private Organization awakeOrg(String legalName, String legalContact) {
        int offset = 12 - Instant.now().atZone(ZoneOffset.UTC).getHour();
        Organization o = new Organization();
        o.setName("Dispatch Org");
        o.setSlug("lid-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("lid@test.com");
        o.setCountry("FR");
        o.setTimezone(ZoneOffset.ofHours(offset).getId());
        o.setLegalName(legalName);
        o.setLegalContact(legalContact);
        return orgs.save(o);
    }

    private Campaign campaignWithPending(Organization org, String origin, String status, Instant updatedAt) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(org.getId());
        c.setChannel("email");
        c.setName("c");
        c.setStatus(status);
        c.setOrigin(origin);
        c.setSubject("S");
        c.setBodyMd("B");
        c.setScheduledAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(updatedAt);
        campaigns.save(c);
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(null);
        r.setEmail(origin + "-" + UUID.randomUUID() + "@example.com");
        r.setStatus("pending");
        recipients.save(r);
        return c;
    }

    private Campaign reload(Campaign c) {
        return campaigns.findById(c.getId()).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<String> sentTo() {
        ArgumentCaptor<List<CampaignEmailProvider.OutgoingEmail>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider, org.mockito.Mockito.atLeast(0)).sendBatch(captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).map(CampaignEmailProvider.OutgoingEmail::to).toList();
    }

    @Test
    void heldCampaignsAreNeverClaimed_andAnotherOrgsManualCampaignStillSends() {
        Organization noIdentity = awakeOrg("Held SAS", null);
        Campaign stale = campaignWithPending(noIdentity, "audience_plan", "sending",
                Instant.now().minus(30, ChronoUnit.MINUTES));
        for (int i = 0; i < 10; i++) {
            campaignWithPending(noIdentity, "audience_plan", "scheduled", Instant.now());
        }
        Campaign manual = campaignWithPending(awakeOrg(null, null), "manual", "scheduled", Instant.now());

        dispatcher.runOnce();
        dispatcher.runOnce();

        assertThat(reload(manual).getStatus()).isEqualTo("sent");
        assertThat(reload(stale).getStatus()).isEqualTo("sending");
        assertThat(sentTo()).hasSize(1).allSatisfy(to -> assertThat(to).startsWith("manual-"));
        assertThat(dispatcher.claimDueCampaignIds(Instant.now())).isEmpty();
    }

    @Test
    void identityRemovedAfterClaim_failsTheCampaignOnce_andItIsNotReclaimed() {
        Organization noIdentity = awakeOrg(null, "legal@x.test");
        Campaign held = campaignWithPending(noIdentity, "audience_plan", "scheduled", Instant.now());

        // Drive it as the dispatcher would after a claim that raced the identity removal.
        sendUnit.processOne(held);

        Campaign after = reload(held);
        assertThat(after.getStatus()).isEqualTo("failed");
        assertThat(after.getLastError()).isEqualTo("ORG_LEGAL_IDENTITY_MISSING");
        assertThat(after.getAttempts()).isEqualTo((short) 1);
        assertThat(recipients.countByCampaignIdAndStatus(held.getId(), "pending")).isEqualTo(1L);

        Campaign manual = campaignWithPending(awakeOrg(null, null), "manual", "scheduled", Instant.now());
        dispatcher.runOnce();
        dispatcher.runOnce();

        assertThat(reload(held).getAttempts()).isEqualTo((short) 1);
        assertThat(reload(held).getStatus()).isEqualTo("failed");
        assertThat(reload(manual).getStatus()).isEqualTo("sent");
        verify(provider, times(1)).sendBatch(anyList());
    }

    @Test
    void identityRestored_failedCampaignIsClaimedAgain() {
        Organization o = awakeOrg(null, "legal@x.test");
        Campaign held = campaignWithPending(o, "audience_plan", "scheduled", Instant.now());
        sendUnit.processOne(held);
        verify(provider, never()).sendBatch(anyList());

        o.setLegalName("Restored SAS");
        orgs.save(o);

        assertThat(dispatcher.claimDueCampaignIds(Instant.now())).containsExactly(held.getId());
    }

    @Test
    void allCampaignsFlag_manualOfOrgWithoutIdentity_isNotClaimed_andAnotherOrgsManualStillSends() {
        props.setLegalIdentityAllCampaigns(true);
        Campaign held = campaignWithPending(awakeOrg(null, null), "manual", "scheduled", Instant.now());
        Campaign ok = campaignWithPending(awakeOrg("Ok SAS", "legal@ok.test"), "momentum", "scheduled", Instant.now());

        dispatcher.runOnce();

        assertThat(reload(held).getStatus()).isEqualTo("scheduled");
        assertThat(reload(ok).getStatus()).isEqualTo("sent");
        assertThat(sentTo()).hasSize(1).allSatisfy(to -> assertThat(to).startsWith("momentum-"));
        assertThat(dispatcher.claimDueCampaignIds(Instant.now())).isEmpty();
    }

    @Test
    void allCampaignsFlagOff_manualOfOrgWithoutIdentity_isClaimed() {
        Campaign manual = campaignWithPending(awakeOrg(null, null), "manual", "scheduled", Instant.now());

        assertThat(dispatcher.claimDueCampaignIds(Instant.now())).containsExactly(manual.getId());
    }
}

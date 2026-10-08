package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

/** An audience-plan campaign whose org lacks a legal identity never loops and never starves other orgs. */
@IminIntegrationTest
class AudiencePlanLegalIdentityDispatchTest {

    // The claim is global, LIMIT 10, ordered by scheduled_at: rows this old sort ahead of other tests' campaigns.
    private static final Instant ANCIENT = Instant.now().minus(3650, ChronoUnit.DAYS);

    @Autowired CampaignDispatcher dispatcher;
    @Autowired CampaignSendUnit sendUnit;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired OrganizationRepository orgs;
    @Autowired AudiencePlanProperties props;
    @Autowired JdbcTemplate jdbc;
    @Autowired CampaignEmailProvider provider;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;

    private final List<UUID> orgIds = new ArrayList<>();
    private final Set<String> ownAddresses = new HashSet<>();

    @BeforeEach
    void setUp() {
        flips.set(props, "sendsEnabled", true);
        when(provider.sendBatch(anyList())).thenAnswer(inv -> {
            List<?> batch = inv.getArgument(0);
            return batch.stream().map(e -> "id-" + UUID.randomUUID()).toList();
        });
    }

    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    /** Local time near noon right now: the dispatcher reads Instant.now(), so quiet hours never drop these. */
    private Organization awakeOrg(String legalName, String legalContact) {
        int offset = 12 - Instant.now().atZone(ZoneOffset.UTC).getHour();
        Organization o = fx.org();
        o.setCountry("FR");
        o.setTimezone(ZoneOffset.ofHours(offset).getId());
        o.setLegalName(legalName);
        o.setLegalContact(legalContact);
        o = orgs.save(o);
        orgIds.add(o.getId());
        return o;
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
        c.setScheduledAt(ANCIENT);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(updatedAt);
        campaigns.save(c);
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(null);
        r.setEmail(fx.email(origin));
        r.setStatus("pending");
        recipients.save(r);
        ownAddresses.add(r.getEmail());
        return c;
    }

    private Campaign reload(Campaign c) {
        return campaigns.findById(c.getId()).orElseThrow();
    }

    private String addressOf(Campaign c) {
        return recipients.findByCampaignIdAndStatus(c.getId(), "pending").stream()
                .map(CampaignRecipient::getEmail).findFirst()
                .orElseGet(() -> recipients.findByCampaignIdAndStatus(c.getId(), "sent").get(0).getEmail());
    }

    /** Addresses this test's campaigns sent to; the dispatcher may also drain another class's leftovers. */
    @SuppressWarnings("unchecked")
    private List<String> sentTo() {
        ArgumentCaptor<List<CampaignEmailProvider.OutgoingEmail>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider, atLeast(0)).sendBatch(captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream)
                .map(CampaignEmailProvider.OutgoingEmail::to).filter(ownAddresses::contains).toList();
    }

    @Test
    void heldCampaignsAreNeverClaimed_andAnotherOrgsManualCampaignStillSends() {
        Organization noIdentity = awakeOrg("Held SAS", null);
        Campaign stale = campaignWithPending(noIdentity, "audience_plan", "sending",
                Instant.now().minus(30, ChronoUnit.MINUTES));
        List<UUID> held = new ArrayList<>(List.of(stale.getId()));
        for (int i = 0; i < 10; i++) {
            held.add(campaignWithPending(noIdentity, "audience_plan", "scheduled", Instant.now()).getId());
        }
        Campaign manual = campaignWithPending(awakeOrg(null, null), "manual", "scheduled", Instant.now());

        dispatcher.runOnce();
        dispatcher.runOnce();

        assertThat(reload(manual).getStatus()).isEqualTo("sent");
        assertThat(reload(stale).getStatus()).isEqualTo("sending");
        assertThat(sentTo()).hasSize(1).allSatisfy(to -> assertThat(to).startsWith("manual-"));
        assertThat(dispatcher.claimDueCampaignIds(Instant.now()))
                .doesNotContain(manual.getId())
                .doesNotContainAnyElementsOf(held);
    }

    @Test
    void identityRemovedAfterClaim_failsTheCampaignOnce_andItIsNotReclaimed() {
        Organization noIdentity = awakeOrg(null, fx.email("legal"));
        Campaign held = campaignWithPending(noIdentity, "audience_plan", "scheduled", Instant.now());
        // One earlier attempt, so the fail must add one rather than set a constant.
        jdbc.update("UPDATE campaigns SET attempts = 1 WHERE id = ?", held.getId());
        held.setAttempts((short) 1);

        // Drive it as the dispatcher would after a claim that raced the identity removal.
        sendUnit.processOne(held);

        Campaign after = reload(held);
        assertThat(after.getStatus()).isEqualTo("failed");
        assertThat(after.getLastError()).isEqualTo("ORG_LEGAL_IDENTITY_MISSING");
        assertThat(after.getAttempts()).isEqualTo((short) 2);
        assertThat(recipients.countByCampaignIdAndStatus(held.getId(), "pending")).isEqualTo(1L);

        Campaign manual = campaignWithPending(awakeOrg(null, null), "manual", "scheduled", Instant.now());
        String manualAddress = addressOf(manual);
        dispatcher.runOnce();
        dispatcher.runOnce();

        assertThat(reload(held).getAttempts()).isEqualTo((short) 2);
        assertThat(reload(held).getStatus()).isEqualTo("failed");
        assertThat(reload(manual).getStatus()).isEqualTo("sent");
        assertThat(sentTo()).containsExactly(manualAddress);
    }

    @Test
    void identityRestored_failedCampaignIsClaimedAgain() {
        Organization o = awakeOrg(null, fx.email("legal"));
        Campaign held = campaignWithPending(o, "audience_plan", "scheduled", Instant.now());
        sendUnit.processOne(held);
        assertThat(sentTo()).isEmpty();

        o.setLegalName("Restored SAS");
        orgs.save(o);

        assertThat(dispatcher.claimDueCampaignIds(Instant.now())).contains(held.getId());
    }

    @Test
    void allCampaignsFlag_manualOfOrgWithoutIdentity_isNotClaimed_andAnotherOrgsManualStillSends() {
        flips.set(props, "legalIdentityAllCampaigns", true);
        Campaign held = campaignWithPending(awakeOrg(null, null), "manual", "scheduled", Instant.now());
        Campaign ok = campaignWithPending(awakeOrg("Ok SAS", fx.email("legal")), "momentum", "scheduled", Instant.now());

        dispatcher.runOnce();

        assertThat(reload(held).getStatus()).isEqualTo("scheduled");
        assertThat(reload(ok).getStatus()).isEqualTo("sent");
        assertThat(sentTo()).hasSize(1).allSatisfy(to -> assertThat(to).startsWith("momentum-"));
        assertThat(dispatcher.claimDueCampaignIds(Instant.now())).doesNotContain(held.getId(), ok.getId());
    }

    @Test
    void allCampaignsFlagOff_manualOfOrgWithoutIdentity_isClaimed() {
        Campaign manual = campaignWithPending(awakeOrg(null, null), "manual", "scheduled", Instant.now());

        assertThat(dispatcher.claimDueCampaignIds(Instant.now())).contains(manual.getId());
    }
}

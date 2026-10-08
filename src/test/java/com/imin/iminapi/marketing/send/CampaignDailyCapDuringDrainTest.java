package com.imin.iminapi.marketing.send;

import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.MarketingGuardProperties;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * The per-org rolling-24h daily cap holds a due campaign at claim time and stops a drain per batch, leaving
 * the campaign reclaimable; checked at claim time only, one campaign drained past the cap on a shared domain.
 */
@IminIntegrationTest
class CampaignDailyCapDuringDrainTest {

    // A fixed non-quiet instant for UTC orgs, so the claim's quiet-hours gate never interferes.
    private static final Instant AWAKE = Instant.parse("2026-07-14T12:00:00Z");
    // The claim is global, LIMIT 10, ordered by scheduled_at: rows this old sort ahead of other tests' campaigns.
    private static final Instant ANCIENT = AWAKE.minus(3650, ChronoUnit.DAYS);

    @Autowired CampaignDispatcher dispatcher;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired OrganizationRepository orgs;
    @Autowired MarketingGuardProperties guardProps;
    @Autowired CampaignEmailProvider provider;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();

    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    private Organization org(String timezone) {
        Organization o = fx.org();
        o.setTimezone(timezone);
        o = orgs.save(o);
        orgIds.add(o.getId());
        return o;
    }

    private Campaign scheduledCampaign(UUID orgId, Instant scheduledAt) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Over cap");
        c.setStatus("scheduled");
        c.setScheduledAt(scheduledAt);
        c.setSubject("Subject");
        c.setBodyMd("Body");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    private void recipient(Campaign c, String status, Instant lastEventAt) {
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(null);
        r.setEmail(fx.email("cap"));
        r.setStatus(status);
        r.setLastEventAt(lastEventAt);
        recipients.save(r);
    }

    @Test
    void drainStopsAtTheDailyCapAndLeavesTheRestQueued() {
        flips.set(guardProps, "dailyCap", 100);
        // runOnce reads Instant.now(): an offset that puts the org's local time near noon right now.
        String awakeNow = ZoneOffset.ofHours(12 - Instant.now().atZone(ZoneOffset.UTC).getHour()).getId();
        Campaign c = scheduledCampaign(org(awakeNow).getId(), Instant.now().minus(3650, ChronoUnit.DAYS));
        for (int i = 0; i < 150; i++) {   // cap is 100: batch 1 fills it, batch 2 must not run
            recipient(c, "pending", null);
        }
        when(provider.sendBatch(anyList())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(0)).stream().map(x -> "msg-" + UUID.randomUUID()).toList());

        dispatcher.runOnce();

        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "sent")).isEqualTo(100L);
        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "pending")).isEqualTo(50L);
        // Held, not finished: the campaign must stay reclaimable once the window rolls forward.
        Campaign after = campaigns.findByIdAndOrgId(c.getId(), c.getOrgId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("sending");
        assertThat(after.getSentAt()).isNull();
    }

    /** With the cap at 1, one recent send in the rolling window holds the org's due campaign; none claims it. */
    @ParameterizedTest(name = "recent send = {0}")
    @ValueSource(booleans = {true, false})
    void claimHoldsAnOrgAtItsDailyCap(boolean recentSend) {
        flips.set(guardProps, "dailyCap", 1);
        Organization o = org("UTC");
        if (recentSend) {
            recipient(scheduledCampaign(o.getId(), null), "sent", AWAKE.minus(2, ChronoUnit.HOURS));
        }
        Campaign due = scheduledCampaign(o.getId(), ANCIENT);

        List<UUID> claimed = dispatcher.claimDueCampaignIds(AWAKE);

        if (recentSend) assertThat(claimed).doesNotContain(due.getId());
        else assertThat(claimed).contains(due.getId());
    }
}

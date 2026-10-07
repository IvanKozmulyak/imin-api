package com.imin.iminapi.marketing;

import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.send.CampaignDispatcher;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dispatcher's claim decision (spec §2.5 step 4, §7): due-scheduled, retryable-failed, and stale-`sending`
 * reclaim MINUS complaint-paused orgs and orgs inside email quiet hours; the claim method, not the tick.
 */
@IminIntegrationTest
class CampaignDispatcherGatingTest {

    // A fixed non-quiet instant (12:00 UTC) for UTC orgs, and one inside their 22:00–09:00 quiet window.
    private static final Instant AWAKE = Instant.parse("2026-07-14T12:00:00Z");
    private static final Instant QUIET = Instant.parse("2026-07-14T03:00:00Z");
    // The claim is global, LIMIT 10: rows this old sort ahead of other tests' dated campaigns. The defence
    // against leftovers is the CampaignRows cleanup rule, not this order: NULLS FIRST puts null-scheduled ones first.
    private static final Instant ANCIENT = AWAKE.minus(3650, ChronoUnit.DAYS);

    @Autowired CampaignDispatcher dispatcher;
    @Autowired CampaignRepository campaigns;
    @Autowired OrganizationRepository orgs;
    @Autowired JdbcTemplate jdbc;
    @Autowired AudiencePlanProperties planProps;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;

    private final List<UUID> orgIds = new ArrayList<>();

    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    private Organization org(String tz, boolean paused) {
        Organization o = fx.org();
        o.setTimezone(tz);
        if (paused) o.setMarketingPausedAt(Instant.now());
        o = orgs.save(o);
        orgIds.add(o.getId());
        return o;
    }

    // Campaign has no @PrePersist/@UpdateTimestamp, so setUpdatedAt ages the stale-sending seed as written.
    private Campaign campaign(UUID orgId, String status, int attempts, Instant scheduledAt, Instant updatedAt) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("c");
        c.setStatus(status);
        c.setAttempts((short) attempts);
        c.setSubject("S");
        c.setBodyMd("B");
        c.setScheduledAt(scheduledAt);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(updatedAt);
        return campaigns.save(c);
    }

    @Test
    void pausedOrgIsNotClaimed() {
        Campaign paused = campaign(org("UTC", true).getId(), "scheduled", 0, ANCIENT, AWAKE);
        Campaign control = campaign(org("UTC", false).getId(), "scheduled", 0, ANCIENT, AWAKE);

        assertThat(dispatcher.claimDueCampaignIds(AWAKE))
                .contains(control.getId())
                .doesNotContain(paused.getId());
    }

    @Test
    void quietHoursOrgIsNotClaimed() {
        // 03:00 UTC is quiet in a UTC org and midday in Tokyo.
        Campaign quiet = campaign(org("UTC", false).getId(), "scheduled", 0, ANCIENT, QUIET);
        Campaign control = campaign(org("Asia/Tokyo", false).getId(), "scheduled", 0, ANCIENT, QUIET);

        assertThat(dispatcher.claimDueCampaignIds(QUIET))
                .contains(control.getId())
                .doesNotContain(quiet.getId());
    }

    @Test
    void staleSendingCampaignIsReclaimed() {
        Organization o = org("UTC", false);
        Campaign c = campaign(o.getId(), "sending", 1, ANCIENT,
            AWAKE.minus(10, ChronoUnit.MINUTES)); // updated_at stale > 5 min
        assertThat(dispatcher.claimDueCampaignIds(AWAKE)).contains(c.getId());
    }

    @Test
    void failedCampaignAtMaxAttemptsIsNotRetried() {
        Organization o = org("UTC", false);
        Campaign exhausted = campaign(o.getId(), "failed", 3, ANCIENT, AWAKE);
        Campaign lastTry = campaign(o.getId(), "failed", 2, ANCIENT, AWAKE);

        assertThat(dispatcher.claimDueCampaignIds(AWAKE))
                .contains(lastTry.getId())
                .doesNotContain(exhausted.getId());
    }

    @Test
    void failedCampaignUnderMaxAttemptsIsRetried() {
        Organization o = org("UTC", false);
        Campaign c = campaign(o.getId(), "failed", 1, ANCIENT, AWAKE);
        assertThat(dispatcher.claimDueCampaignIds(AWAKE)).contains(c.getId());
    }

    @Test
    void audiencePlanArmStillWaitsOutQuietHoursWithSendsOn() {
        flips.set(planProps, "sendsEnabled", true);
        Organization o = org("UTC", false);
        // The claim query also requires legal identity for an audience_plan campaign.
        o.setLegalName("Disp SAS");
        o.setLegalContact(fx.email("legal"));
        o = orgs.save(o);
        Campaign arm = campaign(o.getId(), "scheduled", 0, ANCIENT, QUIET);
        arm.setOrigin("audience_plan");
        campaigns.save(arm);

        assertThat(dispatcher.claimDueCampaignIds(QUIET)).doesNotContain(arm.getId());
        assertThat(dispatcher.claimDueCampaignIds(AWAKE)).contains(arm.getId());
    }
}

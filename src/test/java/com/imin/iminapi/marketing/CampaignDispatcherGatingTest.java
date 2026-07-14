package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.send.CampaignDispatcher;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 11 — the dispatcher's claim decision (spec §2.5 step 4, §7): due-scheduled,
 * retryable-failed, and stale-`sending` reclaim MINUS complaint-paused orgs and orgs
 * inside email quiet hours. Tests the unit-testable claim method directly rather than
 * the @Scheduled tick.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class CampaignDispatcherGatingTest {

    @Autowired CampaignDispatcher dispatcher;
    @Autowired CampaignRepository campaigns;
    @Autowired OrganizationRepository orgs;
    @Autowired JdbcTemplate jdbc;

    // The claim query is non-org-scoped with a LIMIT — other suite tests leave due/failed/
    // sending campaigns behind that would crowd out (or falsely include) this test's seeds.
    // Start each test from an empty campaign set so claim membership is deterministic.
    @BeforeEach
    void clearCampaigns() {
        jdbc.update("delete from campaign_recipients");
        jdbc.update("delete from campaigns");
    }

    private Organization org(String tz, boolean paused) {
        // Canonical org-create pattern (AudienceSendGateConsentSuppressionTest.org()):
        // name + slug (UNIQUE) + contact_email + country are NOT NULL with no default;
        // id is @GeneratedValue, do not set it.
        Organization o = new Organization();
        o.setName("Disp Org");
        o.setSlug("disp-" + UUID.randomUUID().toString().substring(0, 6));
        o.setContactEmail("disp@test.com");
        o.setCountry("DE");
        o.setTimezone(tz);
        if (paused) o.setMarketingPausedAt(Instant.now());
        return orgs.save(o);
    }

    // `attempts` is a settable short and `updatedAt` is a plain settable Instant column
    // (Campaign has NO @PrePersist/@UpdateTimestamp), so setUpdatedAt(...) takes effect
    // on save and the stale-sending reclaim seed ages the row correctly. created_at is
    // NOT NULL with no JPA-applied default, so it must be set explicitly.
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
        Organization o = org("Europe/Kyiv", true);
        Campaign c = campaign(o.getId(), "scheduled", 0,
            Instant.now().minus(1, ChronoUnit.MINUTES), Instant.now());
        List<UUID> claimed = dispatcher.claimDueCampaignIds(Instant.now());
        assertThat(claimed).doesNotContain(c.getId());
    }

    @Test
    void quietHoursOrgIsNotClaimed() {
        // Anchor a fixed instant that is inside the org-local 22:00–09:00 email quiet
        // window regardless of the machine's own zone: 03:00 UTC in a UTC org.
        Organization o = org("UTC", false);
        Instant quietNow = Instant.parse("2026-07-14T03:00:00Z");
        Campaign c = campaign(o.getId(), "scheduled", 0,
            quietNow.minus(1, ChronoUnit.MINUTES), quietNow);
        List<UUID> claimed = dispatcher.claimDueCampaignIds(quietNow);
        assertThat(claimed).doesNotContain(c.getId());
    }

    // A fixed non-quiet instant (12:00 UTC) used with UTC orgs so the reclaim/retry
    // assertions don't flap when the test happens to run during the machine's local
    // wall-clock quiet window.
    private static final Instant AWAKE = Instant.parse("2026-07-14T12:00:00Z");

    @Test
    void staleSendingCampaignIsReclaimed() {
        Organization o = org("UTC", false);
        Campaign c = campaign(o.getId(), "sending", 1,
            AWAKE.minus(30, ChronoUnit.MINUTES),
            AWAKE.minus(10, ChronoUnit.MINUTES)); // updated_at stale > 5 min
        List<UUID> claimed = dispatcher.claimDueCampaignIds(AWAKE);
        assertThat(claimed).contains(c.getId());
    }

    @Test
    void failedCampaignAtMaxAttemptsIsNotRetried() {
        Organization o = org("UTC", false);
        Campaign c = campaign(o.getId(), "failed", 3,
            AWAKE.minus(1, ChronoUnit.MINUTES), AWAKE);
        List<UUID> claimed = dispatcher.claimDueCampaignIds(AWAKE);
        assertThat(claimed).doesNotContain(c.getId());
    }

    @Test
    void failedCampaignUnderMaxAttemptsIsRetried() {
        Organization o = org("UTC", false);
        Campaign c = campaign(o.getId(), "failed", 1,
            AWAKE.minus(1, ChronoUnit.MINUTES), AWAKE);
        List<UUID> claimed = dispatcher.claimDueCampaignIds(AWAKE);
        assertThat(claimed).contains(c.getId());
    }
}

package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
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
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 11 — per-org daily cap (spec §7). With the cap pinned to 1, an org that already
 * has one recent send in the rolling-24h window has its due campaigns held; an org with
 * no recent sends is still dispatched.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
@TestPropertySource(properties = "imin.marketing.guard.daily-cap=1")
class CampaignDispatcherDailyCapTest {

    @Autowired CampaignDispatcher dispatcher;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired OrganizationRepository orgs;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired JdbcTemplate jdbc;

    // Fixed non-quiet instant with a UTC org so the quiet-hours gate never interferes.
    private static final Instant AWAKE = Instant.parse("2026-07-14T12:00:00Z");

    // Non-org-scoped claim query with a LIMIT — clear residual campaigns from other suite
    // tests so claim membership for this test's org is deterministic.
    @BeforeEach
    void clearCampaigns() {
        jdbc.update("delete from campaign_recipients");
        jdbc.update("delete from campaigns");
    }

    private Organization utcOrg() {
        Organization o = new Organization();
        o.setName("Cap Org");
        o.setSlug("cap-" + UUID.randomUUID().toString().substring(0, 6));
        o.setContactEmail("cap@test.com");
        o.setCountry("DE");
        o.setTimezone("UTC");
        return orgs.save(o);
    }

    private Campaign scheduledCampaign(UUID orgId) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("c");
        c.setStatus("scheduled");
        c.setScheduledAt(AWAKE.minus(1, ChronoUnit.MINUTES));
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(AWAKE);
        return campaigns.save(c);
    }

    /** Seed a recent 'sent' recipient row for {@code orgId} so its rolling-24h count is >=1. */
    private void seedRecentSend(UUID orgId) {
        Consumer con = new Consumer();
        con.setNormalizedEmail("cap-" + UUID.randomUUID() + "@example.com");
        con = consumers.save(con);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(con.getConsumerId());
        m.setStatus("subscribed");
        m = memberships.save(m);

        Campaign prior = scheduledCampaign(orgId);
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(prior.getId());
        r.setMembershipId(m.getMembershipId());
        r.setEmail("m@example.com");
        r.setStatus("sent");
        r.setLastEventAt(AWAKE.minus(2, ChronoUnit.HOURS));
        recipients.save(r);
    }

    @Test
    void orgAtDailyCapIsHeld() {
        Organization o = utcOrg();
        seedRecentSend(o.getId());
        Campaign due = scheduledCampaign(o.getId());
        List<UUID> claimed = dispatcher.claimDueCampaignIds(AWAKE);
        assertThat(claimed).doesNotContain(due.getId());
    }

    @Test
    void orgUnderDailyCapIsDispatched() {
        Organization o = utcOrg();
        Campaign due = scheduledCampaign(o.getId());
        List<UUID> claimed = dispatcher.claimDueCampaignIds(AWAKE);
        assertThat(claimed).contains(due.getId());
    }
}

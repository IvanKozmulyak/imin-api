package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.model.ProviderEvent;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.repository.ProviderEventRepository;
import com.imin.iminapi.marketing.service.ComplaintRateBreaker;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IminIntegrationTest
class ComplaintRateBreakerTest {

    @Autowired ComplaintRateBreaker breaker;
    @Autowired ProviderEventRepository providerEvents;
    @Autowired OrganizationRepository orgs;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired AuditRows audit;

    private final Set<UUID> campaignIds = new LinkedHashSet<>();
    private final List<UUID> orgIds = new ArrayList<>();

    @AfterEach
    void deleteOwnProviderEvents() {
        // Thousands of rows per test; the payload-retention sweep and every count would otherwise walk them.
        for (UUID id : campaignIds) jdbc.update("delete from provider_events where campaign_id = ?", id);
        CampaignRows.delete(jdbc, orgIds);
    }

    private void event(UUID campaignId, String type) {
        event(campaignId, type, null, Instant.now());
    }

    private void event(UUID campaignId, String type, UUID recipientId, Instant at) {
        campaignIds.add(campaignId);
        ProviderEvent e = new ProviderEvent();
        e.setId(UUID.randomUUID());
        e.setProvider("resend");
        e.setProviderEventId("svix_" + UUID.randomUUID());
        e.setCampaignId(campaignId);
        e.setRecipientId(recipientId);
        e.setType(type);
        e.setCreatedAt(at);
        providerEvents.save(e);
    }

    /** {@code n} events committed together: the same rows as one save each, without a commit per row. */
    private void events(UUID campaignId, String type, int n) {
        events(campaignId, type, n, Instant.now());
    }

    private void events(UUID campaignId, String type, int n, Instant at) {
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < n; i++) event(campaignId, type, null, at);
        });
    }

    /** A sent campaign of the org: the org window reaches events through their campaign. */
    private UUID campaignOf(Organization o) {
        orgIds.add(o.getId());
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(o.getId());
        c.setChannel("email");
        c.setName("Breaker " + c.getId());
        c.setStatus("sent");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c).getId();
    }

    /** {@code n} hard bounces: rows the projector marked hard_bounce, each with its bounce event. */
    private void hardBounces(UUID campaignId, int n, Instant at) {
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < n; i++) {
                CampaignRecipient r = new CampaignRecipient();
                r.setId(UUID.randomUUID());
                r.setCampaignId(campaignId);
                r.setEmail(fx.email("bounce"));
                r.setStatus("bounced");
                r.setErrorCode("hard_bounce");
                recipients.save(r);
                event(campaignId, ProviderEvent.TYPE_BOUNCED, r.getId(), at);
            }
        });
    }

    private String pausedReason(UUID orgId) {
        return jdbc.queryForObject("select marketing_paused_reason from organizations where id = ?", String.class, orgId);
    }

    private Organization seedOrg() {
        Organization o = fx.org();
        o.setTimezone("Europe/Kyiv");
        return orgs.save(o);
    }

    @Test
    void tripsWhenComplaintRateExceedsThresholdAboveFloor() {
        Organization o = seedOrg();
        UUID campaignId = UUID.randomUUID();
        events(campaignId, "email.delivered", 2000);
        events(campaignId, "email.complained", 5); // 0.25% > 0.1%
        breaker.evaluate(campaignId, o.getId());
        assertThat(orgs.findById(o.getId()).orElseThrow().getMarketingPausedAt()).isNotNull();
    }

    @Test
    void doesNotTripBelowVolumeFloor() {
        Organization o = seedOrg();
        UUID campaignId = UUID.randomUUID();
        event(campaignId, "email.delivered");
        event(campaignId, "email.delivered");
        event(campaignId, "email.complained"); // 33% but only 3 events — below floor
        breaker.evaluate(campaignId, o.getId());
        assertThat(orgs.findById(o.getId()).orElseThrow().getMarketingPausedAt()).isNull();
    }

    @Test
    void doesNotTripBelowRateThreshold() {
        Organization o = seedOrg();
        UUID campaignId = UUID.randomUUID();
        events(campaignId, "email.delivered", 5000);
        event(campaignId, "email.complained"); // 0.02% < 0.1%
        breaker.evaluate(campaignId, o.getId());
        assertThat(orgs.findById(o.getId()).orElseThrow().getMarketingPausedAt()).isNull();
    }

    /** Five campaigns of 50 each never reach the per-campaign floor; together they are over it. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"complaints", "hardBounces", "olderThanTheWindow"})
    void tripsOnTheOrgWindow(String scenario) {
        Organization o = seedOrg();
        Instant at = "olderThanTheWindow".equals(scenario) ? Instant.now().minus(Duration.ofDays(31)) : Instant.now();
        List<UUID> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) five.add(campaignOf(o));
        if ("hardBounces".equals(scenario)) {
            for (UUID c : five) events(c, ProviderEvent.TYPE_DELIVERED, 38, at);
            hardBounces(five.get(0), 10, at);   // 10 of 200 delivered + bounced = 5%
        } else {
            for (UUID c : five) events(c, ProviderEvent.TYPE_DELIVERED, 50, at);
            events(five.get(0), ProviderEvent.TYPE_COMPLAINED, 1, at);   // 1 of 250 = 0.4%
        }

        breaker.evaluate(five.get(0), o.getId());

        Organization after = orgs.findById(o.getId()).orElseThrow();
        switch (scenario) {
            case "complaints" -> {
                assertThat(after.getMarketingPausedAt()).isNotNull();
                assertThat(pausedReason(o.getId())).isEqualTo("complaint_rate");
                audit.assertRecorded(o.getId(), "MARKETING_SENDING_PAUSED", "organization", o.getId());
            }
            case "hardBounces" -> {
                assertThat(after.getMarketingPausedAt()).isNotNull();
                assertThat(pausedReason(o.getId())).isEqualTo("bounce_rate");
                audit.assertRecorded(o.getId(), "MARKETING_SENDING_PAUSED", "organization", o.getId());
            }
            default -> {
                assertThat(after.getMarketingPausedAt()).isNull();
                assertThat(audit.forOrg(o.getId())).noneMatch(r -> "MARKETING_SENDING_PAUSED".equals(r.getAction()));
            }
        }
    }

    @Test
    void aResumedOrgIsJudgedOnlyOnLaterMail() {
        Organization o = seedOrg();
        jdbc.update("update organizations set marketing_resumed_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(1))), o.getId());
        UUID before = campaignOf(o);
        Instant twoHoursAgo = Instant.now().minus(Duration.ofHours(2));
        events(before, ProviderEvent.TYPE_DELIVERED, 300, twoHoursAgo);
        events(before, ProviderEvent.TYPE_COMPLAINED, 5, twoHoursAgo);   // would trip, but predates the resume

        breaker.evaluate(before, o.getId());
        assertThat(orgs.findById(o.getId()).orElseThrow().getMarketingPausedAt()).isNull();

        UUID after = campaignOf(o);
        events(after, ProviderEvent.TYPE_DELIVERED, 200);
        events(after, ProviderEvent.TYPE_COMPLAINED, 1);   // 1 of 200 since the resume = 0.5%

        breaker.evaluate(after, o.getId());
        assertThat(orgs.findById(o.getId()).orElseThrow().getMarketingPausedAt()).isNotNull();
        assertThat(pausedReason(o.getId())).isEqualTo("complaint_rate");
    }
}

package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.model.ProviderEvent;
import com.imin.iminapi.marketing.repository.ProviderEventRepository;
import com.imin.iminapi.marketing.service.ComplaintRateBreaker;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashSet;
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

    private final Set<UUID> campaignIds = new LinkedHashSet<>();

    @AfterEach
    void deleteOwnProviderEvents() {
        // Thousands of rows per test; the payload-retention sweep and every count would otherwise walk them.
        for (UUID id : campaignIds) jdbc.update("delete from provider_events where campaign_id = ?", id);
    }

    private void event(UUID campaignId, String type) {
        campaignIds.add(campaignId);
        ProviderEvent e = new ProviderEvent();
        e.setId(UUID.randomUUID());
        e.setProvider("resend");
        e.setProviderEventId("svix_" + UUID.randomUUID());
        e.setCampaignId(campaignId);
        e.setType(type);
        e.setCreatedAt(Instant.now());
        providerEvents.save(e);
    }

    /** {@code n} events committed together: the same rows as one save each, without a commit per row. */
    private void events(UUID campaignId, String type, int n) {
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < n; i++) event(campaignId, type);
        });
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
}

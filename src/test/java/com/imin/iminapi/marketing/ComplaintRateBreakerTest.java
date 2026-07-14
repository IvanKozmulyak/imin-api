package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.model.ProviderEvent;
import com.imin.iminapi.marketing.repository.ProviderEventRepository;
import com.imin.iminapi.marketing.service.ComplaintRateBreaker;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class ComplaintRateBreakerTest {

    @Autowired ComplaintRateBreaker breaker;
    @Autowired ProviderEventRepository providerEvents;
    @Autowired OrganizationRepository orgs;

    private void event(UUID campaignId, String type) {
        ProviderEvent e = new ProviderEvent();
        e.setId(UUID.randomUUID());
        e.setProvider("resend");
        e.setProviderEventId("svix_" + UUID.randomUUID());
        e.setCampaignId(campaignId);
        e.setType(type);
        e.setCreatedAt(Instant.now());
        providerEvents.save(e);
    }

    private Organization seedOrg() {
        // organizations.contact_email VARCHAR(320) NOT NULL and country VARCHAR(2)
        // NOT NULL have no DB/entity default (V5__auth_and_org.sql:3-5;
        // Organization.java defaults only timezone/plan/currency), and slug is
        // NOT NULL + UNIQUE (V15__organization_slug.sql). Set all three exactly like
        // the canonical helper AudienceSendGateConsentSuppressionTest.org():531-537.
        // id is @GeneratedValue — do not set it.
        Organization o = new Organization();
        o.setName("Breaker Test Org");
        o.setSlug("breaker-" + UUID.randomUUID().toString().substring(0, 6));
        o.setContactEmail("breaker@test.com");
        o.setCountry("DE");
        o.setTimezone("Europe/Kyiv");
        return orgs.save(o);
    }

    @Test
    void tripsWhenComplaintRateExceedsThresholdAboveFloor() {
        Organization o = seedOrg();
        UUID campaignId = UUID.randomUUID();
        for (int i = 0; i < 2000; i++) event(campaignId, "email.delivered");
        for (int i = 0; i < 5; i++) event(campaignId, "email.complained"); // 0.25% > 0.1%
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
        for (int i = 0; i < 5000; i++) event(campaignId, "email.delivered");
        event(campaignId, "email.complained"); // 0.02% < 0.1%
        breaker.evaluate(campaignId, o.getId());
        assertThat(orgs.findById(o.getId()).orElseThrow().getMarketingPausedAt()).isNull();
    }
}

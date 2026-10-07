package com.imin.iminapi.marketing;

import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Audience-plan campaigns need the org's legal name and contact; manual and Momentum ones only with the all-campaigns flag. */
@IminIntegrationTest
class AudiencePlanLegalIdentityGuardTest {

    @Autowired CampaignService service;
    @Autowired CampaignRepository campaigns;
    @Autowired OrganizationRepository orgs;
    @Autowired AudiencePlanProperties props;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();

    @BeforeEach
    void sendsOn() {
        // The sends switch answers first; turn it on so the legal-identity guard is what decides.
        flips.set(props, "sendsEnabled", true);
    }

    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    private Organization org(String legalName, String legalContact) {
        Organization o = fx.org();
        o.setTimezone("UTC");
        o.setLegalName(legalName);
        o.setLegalContact(legalContact);
        o = orgs.save(o);
        orgIds.add(o.getId());
        return o;
    }

    private AuthPrincipal owner(Organization org) {
        return fx.principal(fx.owner(org));
    }

    private Campaign campaign(Organization org, String origin, String status) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(org.getId());
        c.setChannel("email");
        c.setName("c");
        c.setStatus(status);
        c.setOrigin(origin);
        c.setAttempts((short) ("failed".equals(status) ? 1 : 0));
        c.setSubject("S");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    private String status(Campaign c) {
        return campaigns.findById(c.getId()).orElseThrow().getStatus();
    }

    private static void assertLegalIdentityMissing(Throwable t) {
        assertThat(t).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo(ErrorCode.ORG_LEGAL_IDENTITY_MISSING);
            assertThat(e.getMessage())
                    .isEqualTo("Add the organization's legal name and legal contact before scheduling this campaign");
        });
    }

    /** Every API entry to the send state machine × every way the identity can be missing. */
    @ParameterizedTest(name = "{0} with legalName={1} legalContact={2}")
    @CsvSource(nullValues = "NULL", value = {
            "sendNow,  NULL,      legal",
            "schedule, NULL,      legal",
            "sendNow,  Guard SAS, NULL",
            "sendNow,  '  ',      legal",
            "retry,    NULL,      NULL",
    })
    void audiencePlan_withoutLegalIdentity_409_stateKept(String action, String legalName, String contact) {
        Organization o = org(legalName, contact == null ? null : fx.email(contact));
        Campaign c = campaign(o, "audience_plan", "retry".equals(action) ? "failed" : "draft");

        assertLegalIdentityMissing(catchThrowable(() -> act(action, o, c)));
        Campaign after = campaigns.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("retry".equals(action) ? "failed" : "draft");
        assertThat(after.getScheduledAt()).isNull();
    }

    private void act(String action, Organization o, Campaign c) {
        switch (action) {
            case "sendNow" -> service.send(c.getId(), owner(o), "idem-" + UUID.randomUUID(), null);
            case "schedule" -> service.send(c.getId(), owner(o), "idem-" + UUID.randomUUID(),
                    Instant.now().plus(2, ChronoUnit.DAYS));
            case "retry" -> service.retry(owner(o), c.getId());
            default -> throw new IllegalArgumentException(action);
        }
    }

    @Test
    void audiencePlan_withLegalIdentity_schedulesAndRetries() {
        Organization o = org("Guard SAS", fx.email("legal"));
        Campaign draft = campaign(o, "audience_plan", "draft");
        Campaign failed = campaign(o, "audience_plan", "failed");

        service.send(draft.getId(), owner(o), "idem-" + UUID.randomUUID(), null);
        service.retry(owner(o), failed.getId());

        assertThat(status(draft)).isEqualTo("scheduled");
        assertThat(status(failed)).isEqualTo("scheduled");
    }

    @Test
    void manualAndMomentum_withoutLegalIdentity_scheduleAndRetry() {
        Organization o = org(null, null);
        Campaign manual = campaign(o, "manual", "draft");
        Campaign momentum = campaign(o, "momentum", "draft");
        Campaign failedManual = campaign(o, "manual", "failed");

        service.send(manual.getId(), owner(o), "idem-" + UUID.randomUUID(), null);
        service.send(momentum.getId(), owner(o), "idem-" + UUID.randomUUID(), null);
        service.retry(owner(o), failedManual.getId());

        assertThat(status(manual)).isEqualTo("scheduled");
        assertThat(status(momentum)).isEqualTo("scheduled");
        assertThat(status(failedManual)).isEqualTo("scheduled");
    }

    @Test
    void allCampaignsFlag_manualAndMomentumWithoutIdentity_sendScheduleRetry_409_stateKept() {
        flips.set(props, "legalIdentityAllCampaigns", true);
        Organization o = org(null, fx.email("legal"));
        Campaign manual = campaign(o, "manual", "draft");
        Campaign momentum = campaign(o, "momentum", "draft");
        Campaign failedManual = campaign(o, "manual", "failed");

        assertLegalIdentityMissing(catchThrowable(
                () -> service.send(manual.getId(), owner(o), "idem-" + UUID.randomUUID(), null)));
        assertLegalIdentityMissing(catchThrowable(() -> service.send(momentum.getId(), owner(o),
                "idem-" + UUID.randomUUID(), Instant.now().plus(2, ChronoUnit.DAYS))));
        assertLegalIdentityMissing(catchThrowable(() -> service.retry(owner(o), failedManual.getId())));

        assertThat(status(manual)).isEqualTo("draft");
        assertThat(campaigns.findById(momentum.getId()).orElseThrow().getScheduledAt()).isNull();
        assertThat(status(momentum)).isEqualTo("draft");
        assertThat(status(failedManual)).isEqualTo("failed");
    }

    @Test
    void allCampaignsFlag_manualWithIdentity_schedulesAndRetries() {
        flips.set(props, "legalIdentityAllCampaigns", true);
        Organization o = org("Guard SAS", fx.email("legal"));
        Campaign manual = campaign(o, "manual", "draft");
        Campaign failedMomentum = campaign(o, "momentum", "failed");

        service.send(manual.getId(), owner(o), "idem-" + UUID.randomUUID(), null);
        service.retry(owner(o), failedMomentum.getId());

        assertThat(status(manual)).isEqualTo("scheduled");
        assertThat(status(failedMomentum)).isEqualTo("scheduled");
    }
}

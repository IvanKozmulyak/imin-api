package com.imin.iminapi.marketing;

import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Audience-plan campaigns need the org's legal name and contact; manual and Momentum sends do not. */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AudiencePlanLegalIdentityGuardTest {

    @Autowired CampaignService service;
    @Autowired CampaignRepository campaigns;
    @Autowired OrganizationRepository orgs;
    @Autowired AudiencePlanProperties props;
    @MockitoBean AuditLogger audit;

    @BeforeEach
    void sendsOn() {
        // The sends switch answers first; turn it on so the legal-identity guard is what decides.
        props.setSendsEnabled(true);
    }

    @AfterEach
    void restore() {
        props.setSendsEnabled(false);
    }

    private Organization org(String legalName, String legalContact) {
        Organization o = new Organization();
        o.setName("Guard Org");
        o.setSlug("lg-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("lg@test.com");
        o.setCountry("FR");
        o.setTimezone("UTC");
        o.setLegalName(legalName);
        o.setLegalContact(legalContact);
        return orgs.save(o);
    }

    private static AuthPrincipal owner(Organization org) {
        return new AuthPrincipal(UUID.randomUUID(), org.getId(), UserRole.OWNER, UUID.randomUUID());
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

    @Test
    void audiencePlan_withoutLegalName_sendNow_409_staysDraft() {
        Organization o = org(null, "legal@guard.test");
        Campaign c = campaign(o, "audience_plan", "draft");

        assertLegalIdentityMissing(catchThrowable(
                () -> service.send(c.getId(), owner(o), "idem-" + UUID.randomUUID(), null)));
        assertThat(status(c)).isEqualTo("draft");
    }

    @Test
    void audiencePlan_withoutLegalName_schedule_409_staysDraft() {
        Organization o = org(null, "legal@guard.test");
        Campaign c = campaign(o, "audience_plan", "draft");

        assertLegalIdentityMissing(catchThrowable(() -> service.send(c.getId(), owner(o),
                "idem-" + UUID.randomUUID(), Instant.now().plus(2, ChronoUnit.DAYS))));
        Campaign after = campaigns.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("draft");
        assertThat(after.getScheduledAt()).isNull();
    }

    @Test
    void audiencePlan_withoutLegalContact_sendNow_409() {
        Organization o = org("Guard SAS", null);
        Campaign c = campaign(o, "audience_plan", "draft");

        assertLegalIdentityMissing(catchThrowable(
                () -> service.send(c.getId(), owner(o), "idem-" + UUID.randomUUID(), null)));
    }

    @Test
    void audiencePlan_withBlankLegalName_sendNow_409() {
        Organization o = org("  ", "legal@guard.test");
        Campaign c = campaign(o, "audience_plan", "draft");

        assertLegalIdentityMissing(catchThrowable(
                () -> service.send(c.getId(), owner(o), "idem-" + UUID.randomUUID(), null)));
    }

    @Test
    void audiencePlan_withoutLegalIdentity_retry_409_staysFailed() {
        Organization o = org(null, null);
        Campaign c = campaign(o, "audience_plan", "failed");

        assertLegalIdentityMissing(catchThrowable(() -> service.retry(owner(o), c.getId())));
        assertThat(status(c)).isEqualTo("failed");
    }

    @Test
    void audiencePlan_withLegalIdentity_schedulesAndRetries() {
        Organization o = org("Guard SAS", "legal@guard.test");
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
}

package com.imin.iminapi.marketing;

import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.marketing.dto.CampaignDto;
import com.imin.iminapi.marketing.dto.CampaignRequests.PatchCampaignRequest;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.send.CampaignDispatcher;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** The audience-plan sends switch on every path that can make a campaign send. */
@IminIntegrationTest
class AudiencePlanSendsKillSwitchTest {

    // A non-quiet instant for a UTC org, so the dispatcher's quiet-hours skip never interferes.
    private static final Instant AWAKE = Instant.parse("2026-07-14T12:00:00Z");
    // The claim is global, LIMIT 10: rows this old sort ahead of other tests' dated campaigns. The defence
    // against leftovers is the CampaignRows cleanup rule, not this order: NULLS FIRST puts null-scheduled ones first.
    private static final Instant ANCIENT = AWAKE.minus(3650, ChronoUnit.DAYS);

    @Autowired CampaignService service;
    @Autowired CampaignDispatcher dispatcher;
    @Autowired CampaignRepository campaigns;
    @Autowired OrganizationRepository orgs;
    @Autowired AudiencePlanProperties props;
    @Autowired JdbcTemplate jdbc;
    @Autowired IminFixtures fx;
    @Autowired AuditRows audit;
    @Autowired PropertyFlips flips;

    private Organization org;
    private final List<UUID> orgIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Organization o = fx.org();
        o.setTimezone("UTC");
        // Legal identity present so only the sends switch decides here.
        o.setLegalName("Kill Switch SAS");
        o.setLegalContact(fx.email("legal"));
        org = orgs.save(o);
        orgIds.add(org.getId());
    }

    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    private AuthPrincipal owner() {
        return fx.principal(fx.owner(org));
    }

    private Campaign campaign(String origin, String status, Instant scheduledAt) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(org.getId());
        c.setChannel("email");
        c.setName("c");
        c.setStatus(status);
        c.setOrigin(origin);
        c.setAttempts((short) (("failed".equals(status)) ? 1 : 0));
        c.setSubject("S");
        c.setBodyMd("B");
        c.setScheduledAt(scheduledAt);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    private String status(Campaign c) {
        return campaigns.findById(c.getId()).orElseThrow().getStatus();
    }

    private static void assertSendsDisabled(Throwable t) {
        assertThat(t).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo(ErrorCode.AUDIENCE_SENDS_DISABLED);
            assertThat(e.getMessage()).isEqualTo("Sending audience plan campaigns is not enabled yet");
        });
    }

    @Test
    void flagOff_sendNow_409_staysDraft_noDispatchAudit() {
        Campaign c = campaign("audience_plan", "draft", null);

        Throwable t = catchThrowable(
                () -> service.send(c.getId(), owner(), "idem-" + UUID.randomUUID(), null));

        assertSendsDisabled(t);
        assertThat(status(c)).isEqualTo("draft");
        assertThat(audit.forOrg(org.getId())).noneMatch(r -> c.getId().equals(r.getTargetId()));
    }

    @Test
    void flagOff_schedule_409_staysDraft() {
        Campaign c = campaign("audience_plan", "draft", null);

        Throwable t = catchThrowable(() -> service.send(c.getId(), owner(),
                "idem-" + UUID.randomUUID(), Instant.now().plus(2, ChronoUnit.DAYS)));

        assertSendsDisabled(t);
        Campaign after = campaigns.findById(c.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("draft");
        assertThat(after.getScheduledAt()).isNull();
    }

    @Test
    void flagOff_draftStaysEditable() {
        Campaign c = campaign("audience_plan", "draft", null);

        CampaignDto patched = service.patch(owner(), c.getId(),
                new PatchCampaignRequest("Renamed", null, null, "New subject", null, null, null));

        assertThat(patched.name()).isEqualTo("Renamed");
        assertThat(patched.subject()).isEqualTo("New subject");
        assertThat(patched.status()).isEqualTo("draft");
    }

    @Test
    void flagOff_retry_409_staysFailed() {
        Campaign c = campaign("audience_plan", "failed", AWAKE);

        Throwable t = catchThrowable(() -> service.retry(owner(), c.getId()));

        assertSendsDisabled(t);
        assertThat(status(c)).isEqualTo("failed");
    }

    @Test
    void flagOn_sendSchedulesNormally() {
        flips.set(props, "sendsEnabled", true);
        Campaign c = campaign("audience_plan", "draft", null);

        service.send(c.getId(), owner(), "idem-" + UUID.randomUUID(), null);

        assertThat(status(c)).isEqualTo("scheduled");
        audit.assertRecorded(org.getId(), AuditActions.CAMPAIGN_SENT, "campaign", c.getId());
    }

    @Test
    void flagOn_retryRequeuesNormally() {
        flips.set(props, "sendsEnabled", true);
        Campaign c = campaign("audience_plan", "failed", AWAKE);

        service.retry(owner(), c.getId());

        assertThat(status(c)).isEqualTo("scheduled");
    }

    @Test
    void flagOff_manualAndMomentumSendNormally() {
        Campaign manual = campaign("manual", "draft", null);
        Campaign momentum = campaign("momentum", "draft", null);

        service.send(manual.getId(), owner(), "idem-" + UUID.randomUUID(), null);
        service.send(momentum.getId(), owner(), "idem-" + UUID.randomUUID(), null);

        assertThat(status(manual)).isEqualTo("scheduled");
        assertThat(status(momentum)).isEqualTo("scheduled");
    }

    @Test
    void flagOff_manualRetryRequeuesNormally() {
        Campaign manual = campaign("manual", "failed", AWAKE);

        service.retry(owner(), manual.getId());

        assertThat(status(manual)).isEqualTo("scheduled");
    }

    @Test
    void flagOff_dispatcherHoldsDueAudiencePlanCampaigns_butClaimsManual() {
        Campaign scheduled = campaign("audience_plan", "scheduled", ANCIENT);
        Campaign failed = campaign("audience_plan", "failed", ANCIENT);
        Campaign manual = campaign("manual", "scheduled", ANCIENT);

        assertThat(dispatcher.claimDueCampaignIds(AWAKE))
                .contains(manual.getId())
                .doesNotContain(scheduled.getId(), failed.getId());
    }

    @Test
    void flagOff_staleSendingAudiencePlanCampaignIsNotReclaimed() {
        Campaign stale = campaign("audience_plan", "sending", ANCIENT);
        stale.setUpdatedAt(AWAKE.minus(10, ChronoUnit.MINUTES));
        campaigns.save(stale);

        assertThat(dispatcher.claimDueCampaignIds(AWAKE)).doesNotContain(stale.getId());
        flips.set(props, "sendsEnabled", true);
        assertThat(dispatcher.claimDueCampaignIds(AWAKE)).contains(stale.getId());
    }

    @Test
    void flagOff_heldAudiencePlanCampaignsDoNotCrowdOutTheClaimLimit() {
        List<UUID> held = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            held.add(campaign("audience_plan", "scheduled", ANCIENT.minus(1, ChronoUnit.MINUTES)).getId());
        }
        Campaign manual = campaign("manual", "scheduled", ANCIENT);

        assertThat(dispatcher.claimDueCampaignIds(AWAKE))
                .contains(manual.getId())
                .doesNotContainAnyElementsOf(held);
    }

    @Test
    void flagOn_dispatcherClaimsDueAudiencePlanCampaign() {
        flips.set(props, "sendsEnabled", true);
        Campaign scheduled = campaign("audience_plan", "scheduled", ANCIENT);

        assertThat(dispatcher.claimDueCampaignIds(AWAKE)).contains(scheduled.getId());
    }

    @Test
    void duplicate_keepsAudiencePlanOrigin_soTheCopyIsAlsoHeld() {
        Campaign c = campaign("audience_plan", "draft", null);

        CampaignDto copy = service.duplicate(owner(), c.getId());

        assertThat(copy.origin()).isEqualTo("audience_plan");
        Throwable t = catchThrowable(
                () -> service.send(copy.id(), owner(), "idem-" + UUID.randomUUID(), null));
        assertSendsDisabled(t);
    }

    @Test
    void duplicate_ofMomentum_isManual() {
        Campaign c = campaign("momentum", "draft", null);

        assertThat(service.duplicate(owner(), c.getId()).origin()).isEqualTo("manual");
    }
}

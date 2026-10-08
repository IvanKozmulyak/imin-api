package com.imin.iminapi.marketing.send;

import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.AuditRows;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mkt-core-2 (P1): a campaign whose recipients all exhausted their send attempts must end
 * {@code failed}, not {@code sent}. Stamping it {@code sent} reported 0 sent against a
 * non-zero recipientCount AND locked the organizer out of {@code /retry}, which only
 * accepts {@code failed}.
 */
@IminIntegrationTest
class CampaignSendOutcomeTest {

    @Autowired CampaignDispatcher dispatcher;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired OrganizationRepository orgs;
    @Autowired CampaignService campaignService;
    @Autowired IminFixtures fx;
    @Autowired AuditRows audit;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();

    /** Both tests leave a failed or re-scheduled campaign, which the global claim would pick up later. */
    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    private Organization awakeOrg() {
        int hourNowUtc = Instant.now().atZone(ZoneOffset.UTC).getHour();
        Organization o = fx.org();
        o.setTimezone(ZoneOffset.ofHours(12 - hourNowUtc).getId());
        o = orgs.save(o);
        orgIds.add(o.getId());
        return o;
    }

    /** A due campaign whose recipients have already burned their full attempt budget. */
    private Campaign dueCampaignWithExhaustedRecipients(int n) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(awakeOrg().getId());
        c.setChannel("email");
        c.setName("Doomed blast");
        c.setStatus("scheduled");
        c.setScheduledAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        c.setSubject("Subject");
        c.setBodyMd("Body");
        c.setRecipientCount(n);
        Instant now = Instant.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        campaigns.save(c);
        for (int i = 0; i < n; i++) {
            CampaignRecipient r = new CampaignRecipient();
            r.setId(UUID.randomUUID());
            r.setCampaignId(c.getId());
            r.setMembershipId(null);
            r.setEmail("doomed-" + UUID.randomUUID() + "@example.com");
            r.setStatus("pending");
            r.setAttemptCount((short) 3);   // provider refused this row three times already
            recipients.save(r);
        }
        return c;
    }

    @Test
    void zeroSentAndExhaustedRowsEndsFailedAndStaysRetryable() {
        Campaign c = dueCampaignWithExhaustedRecipients(2);

        dispatcher.runOnce();

        Campaign after = campaigns.findByIdAndOrgId(c.getId(), c.getOrgId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("failed");
        assertThat(after.getSentAt()).isNull();
        assertThat(after.getLastError()).isNotBlank();
        // retry()'s guard is status=='failed' && attempts < 3 — both hold.
        assertThat(after.getAttempts()).isLessThan((short) 3);

        // The dead rows say so, rather than sitting in 'pending' for ever.
        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "pending")).isZero();
        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "failed")).isEqualTo(2L);
        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "failed"))
                .allSatisfy(r -> assertThat(r.getErrorCode()).isNotBlank());
        assertThat(campaignService.stats(c.getId()).sent()).isZero();
    }

    @Test
    void retryPutsExhaustedRecipientsBackInTheQueue() {
        Campaign c = dueCampaignWithExhaustedRecipients(2);
        dispatcher.runOnce();

        AuthPrincipal principal = fx.principal(fx.owner(orgs.findById(c.getOrgId()).orElseThrow()));
        campaignService.retry(principal, c.getId());

        audit.assertRecorded(c.getOrgId(), "CAMPAIGN_RETRIED", "campaign", c.getId());

        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "pending")).isEqualTo(2L);
        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "pending"))
                .allSatisfy(r -> assertThat(r.getAttemptCount()).isEqualTo((short) 0));
    }
}

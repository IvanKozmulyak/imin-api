package com.imin.iminapi.marketing.send;

import com.imin.iminapi.marketing.email.CampaignEmailProvider;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

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
    @Autowired CampaignEmailProvider provider;

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
        assertThat(after.getLastError()).isEqualTo("nothing_sent");
        // retry()'s guard is status=='failed' && attempts < 3 — both hold.
        assertThat(after.getAttempts()).isEqualTo((short) 1);

        // The dead rows say so, rather than sitting in 'pending' for ever.
        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "pending")).isZero();
        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "failed")).isEqualTo(2L);
        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "failed"))
                .allSatisfy(r -> assertThat(r.getErrorCode()).isNotBlank());
        assertThat(campaignService.stats(c.getOrgId(), c.getId()).sent()).isZero();
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

    /** A due campaign of {@code n} pending rows at attempt 0, addressed under {@code tag}. */
    private Campaign dueCampaign(int n, String tag) {
        Campaign c = dueCampaignWithExhaustedRecipients(0);
        c.setScheduledAt(Instant.now().minus(3650, ChronoUnit.DAYS));
        campaigns.save(c);
        for (int i = 0; i < n; i++) {
            CampaignRecipient r = new CampaignRecipient();
            r.setId(UUID.randomUUID());
            r.setCampaignId(c.getId());
            r.setEmail(tag + "-" + i + "-" + fx.email("fan"));
            r.setStatus("pending");
            recipients.save(r);
        }
        return c;
    }

    private static boolean ours(List<CampaignEmailProvider.OutgoingEmail> batch, String tag) {
        return batch.stream().anyMatch(e -> e.to().startsWith(tag + "-"));
    }

    private static CampaignEmailProvider.BatchResult idsFor(List<CampaignEmailProvider.OutgoingEmail> batch) {
        return CampaignEmailProvider.BatchResult.accepted(batch.stream().map(e -> msgId(e.to())).toList());
    }

    /** The July prod incident: Resend refused one address and the whole batch, and the campaign, failed. */
    @Test
    void oneRejectedAddressDoesNotFailTheOtherNinetyNine() {
        String tag = "permissive-" + UUID.randomUUID();
        Campaign c = dueCampaign(100, tag);
        java.util.concurrent.atomic.AtomicReference<String> refused = new java.util.concurrent.atomic.AtomicReference<>();
        when(provider.sendBatch(any(), anyList())).thenAnswer(inv -> {
            List<CampaignEmailProvider.OutgoingEmail> batch = inv.getArgument(1);
            if (!ours(batch, tag)) return idsFor(batch);
            refused.set(batch.get(3).to());
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) ids.add(i == 3 ? null : msgId(batch.get(i).to()));
            return new CampaignEmailProvider.BatchResult(ids, java.util.Map.of(3, "Invalid `to` field."));
        });

        dispatcher.runOnce();

        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "failed")).singleElement().satisfies(r -> {
            assertThat(r.getEmail()).isEqualTo(refused.get());
            assertThat(r.getErrorCode()).isEqualTo("provider_rejected");
        });
        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "sent")).hasSize(99)
                .allSatisfy(r -> assertThat(r.getProviderMessageId()).isEqualTo(msgId(r.getEmail())));
        assertThat(campaigns.findStatusById(c.getId())).contains("sent");
    }

    /** A refused key or unverified domain fails the campaign after one call, and Retry sends everyone once fixed. */
    @Test
    void aRefusedKeyFailsTheCampaignAtOnceAndRetryContinuesIt() {
        String tag = "refused-" + UUID.randomUUID();
        Campaign c = dueCampaign(250, tag);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean fixed = new java.util.concurrent.atomic.AtomicBoolean();
        when(provider.sendBatch(any(), anyList())).thenAnswer(inv -> {
            List<CampaignEmailProvider.OutgoingEmail> batch = inv.getArgument(1);
            if (!ours(batch, tag)) return idsFor(batch);
            calls.incrementAndGet();
            if (!fixed.get()) throw new CampaignEmailProvider.CampaignRejected("domain not verified", 403);
            return idsFor(batch);
        });

        dispatcher.runOnce();

        Campaign failed = campaigns.findByIdAndOrgId(c.getId(), c.getOrgId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo("failed");
        assertThat(com.imin.iminapi.marketing.model.CampaignFailureReason.of(failed.getLastError()))
                .isEqualTo("provider_rejected");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(recipients.findByCampaignIdAndStatus(c.getId(), "pending")).hasSize(250).allSatisfy(r -> {
            assertThat(r.getAttemptCount()).isZero();
            assertThat(r.getSendKey()).isNull();
        });

        fixed.set(true);
        AuthPrincipal admin = new AuthPrincipal(fx.owner(orgs.findById(c.getOrgId()).orElseThrow()).getId(),
                c.getOrgId(), com.imin.iminapi.model.UserRole.ADMIN, UUID.randomUUID());
        campaignService.retry(admin, c.getId());
        dispatcher.runOnce();

        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "sent")).isEqualTo(250L);
        assertThat(campaigns.findStatusById(c.getId())).contains("sent");
    }

    /** A provider id derived from the address (the column holds 64 characters), so a shifted id shows. */
    private static String msgId(String to) {
        return "msg-" + UUID.nameUUIDFromBytes(to.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}

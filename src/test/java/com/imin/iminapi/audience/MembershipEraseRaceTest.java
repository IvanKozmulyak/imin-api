package com.imin.iminapi.audience;

import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.DsarService;
import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.webhook.ResendWebhookProjector;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PgFaults;
import com.imin.iminapi.support.PgLocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/** Membership writers that race an Art.17 erase keep it: each re-reads the member under the row lock it writes under. */
@IminIntegrationTest
class MembershipEraseRaceTest {

    private static final String RECTIFIED = "Rectified name";

    @Autowired DsarService dsarService;
    @Autowired AudienceOrderProjector orderProjector;
    @Autowired ConsentService consentService;
    @Autowired ResendWebhookProjector webhookProjector;
    @Autowired CampaignRepository campaigns;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired @Qualifier("taskExecutor") Executor asyncExecutor;
    @Autowired @Qualifier(FanFeatureExecutors.LIVE) Executor fanFeatureExecutor;

    private UUID orgId;
    private AuthPrincipal owner;

    @BeforeEach
    void setUp() {
        orgId = fx.org().getId();
        owner = new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        try {
            AsyncDrain.drain(asyncExecutor);
            AsyncDrain.drain(fanFeatureExecutor);
        } finally {
            try {
                jdbc.update("delete from marketing_optouts where org_id = ?", orgId);
                jdbc.update("delete from suppression_entries where org_id = ?", orgId);
                jdbc.update("delete from memberships where org_id = ?", orgId);
            } finally {
                try {
                    CampaignRows.delete(jdbc, List.of(orgId));
                } finally {
                    OrgRows.delete(jdbc, List.of(orgId));
                }
            }
        }
    }

    @Test
    void rectifyReadBeforeAnEraseCommits_keepsTheErase() throws Exception {
        UUID mid = subscribedMember(fx.email("rectify"));

        try (PgFaults.Pause pause = PgFaults.pauseWrites(dataSource, "consent_records", "membership_id", mid)) {
            // The erase holds the row lock and is held before its unsubscribe record is written.
            CompletableFuture<Void> erase = CompletableFuture.runAsync(
                    () -> dsarService.requestErase(orgId, mid, owner));
            pause.awaitBlocked(Duration.ofSeconds(10));
            CompletableFuture<Void> rectify = CompletableFuture.runAsync(
                    () -> dsarService.rectify(orgId, mid, RECTIFIED, null, null, owner));
            // The rectify waits on the erase's row lock: in its FOR UPDATE, or, with no lock, in its commit UPDATE.
            PgLocks.awaitLockWait(jdbc, "memberships", "rectify waiting on the erase's membership row lock");
            pause.release();
            erase.get(10, TimeUnit.SECONDS);
            rectify.get(10, TimeUnit.SECONDS);
        }

        assertErasedAndRectified(mid);
    }

    @Test
    void spamComplaintReadBeforeAnEraseCommits_keepsTheErase() throws Exception {
        String email = fx.email("complaint");
        UUID mid = subscribedMember(email);
        UUID campaignId = sentCampaign();

        try (PgFaults.Pause pause = PgFaults.pauseWrites(dataSource, "consent_records", "membership_id", mid)) {
            CompletableFuture<Void> erase = CompletableFuture.runAsync(
                    () -> dsarService.requestErase(orgId, mid, owner));
            pause.awaitBlocked(Duration.ofSeconds(10));
            CompletableFuture<Void> complaint = CompletableFuture.runAsync(
                    () -> webhookProjector.projectTaggedComplaint(campaignId, email, Instant.now()));
            // The complaint first waits in its suppression insert's foreign-key check on the locked member.
            PgLocks.awaitLockWait(jdbc, "memberships|suppression_entries",
                    "complaint waiting on the erase's membership row lock");
            pause.release();
            erase.get(10, TimeUnit.SECONDS);
            complaint.get(10, TimeUnit.SECONDS);
        }

        Map<String, Object> row = row(mid);
        assertSoftly(s -> {
            s.assertThat(row.get("status")).as("status").isEqualTo("erase_pending");
            s.assertThat(row.get("erase_at")).as("erase_at").isNotNull();
            s.assertThat(row.get("consent_status")).as("consent_status").isEqualTo("unsubscribed");
            s.assertThat(row.get("objected_profiling")).as("objected_profiling").isEqualTo(true);
            s.assertThat(jdbc.queryForObject("select count(*) from suppression_entries where membership_id = ?"
                            + " and scope = 'marketing' and reason = 'spam'", Integer.class, mid))
                    .as("the complaint's own marketing suppression").isEqualTo(1);
        });
    }

    private void assertErasedAndRectified(UUID mid) {
        Map<String, Object> row = row(mid);
        assertSoftly(s -> {
            s.assertThat(row.get("status")).as("status").isEqualTo("erase_pending");
            s.assertThat(row.get("erase_at")).as("erase_at").isNotNull();
            s.assertThat(row.get("consent_status")).as("consent_status").isEqualTo("unsubscribed");
            s.assertThat(row.get("display_name")).as("display_name").isEqualTo(RECTIFIED);
        });
    }

    /** A member with recorded consent, so a reverted erase also shows as a resubscribe. */
    private UUID subscribedMember(String email) {
        orderProjector.upsertMembership(orgId, email, email);
        UUID mid = jdbc.queryForObject("select m.membership_id from memberships m join consumers c"
                + " on c.consumer_id = m.consumer_id where m.org_id = ? and c.normalized_email = ?",
                UUID.class, orgId, email);
        consentService.capture(orgId, mid, "explicit", "manual", "Organizer recorded consent", "email", null);
        assertThat(row(mid)).containsEntry("status", "active").containsEntry("consent_status", "subscribed");
        return mid;
    }

    private UUID sentCampaign() {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Sent");
        c.setStatus("sent");
        c.setOrigin("manual");
        c.setSubject("S");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c).getId();
    }

    private Map<String, Object> row(UUID mid) {
        return jdbc.queryForMap("select status, erase_at, consent_status, display_name, objected_profiling"
                + " from memberships where membership_id = ?", mid);
    }
}

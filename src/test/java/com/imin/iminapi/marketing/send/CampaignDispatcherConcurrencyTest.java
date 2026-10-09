package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.predictor.service.PredictorMarketingEvents;
import org.assertj.core.api.SoftAssertions;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import com.imin.iminapi.support.PgFaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/** Two dispatcher replicas at once on real Postgres: one claimer per campaign, one email per recipient. */
@IminIntegrationTest
@RecordApplicationEvents
class CampaignDispatcherConcurrencyTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String LOCK_NAME = "campaign_dispatcher";

    @Autowired CampaignDispatcher dispatcher;
    @Autowired CampaignRepository campaigns;
    @Autowired OrganizationRepository orgs;
    @Autowired CampaignEmailProvider provider;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired AudiencePlanAccess audiencePlan;
    @Autowired CampaignSendUnit sendUnit;
    @Autowired DataSource dataSource;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired SegmentRepository segments;
    @Autowired CampaignService campaignService;
    @Autowired MutableClock clock;
    @Autowired RecipientMaterializer materializer;
    @Autowired ApplicationEvents published;
    @Autowired com.imin.iminapi.support.AuditRows auditRows;

    private final List<UUID> orgIds = new ArrayList<>();
    private final ConcurrentLinkedQueue<String> sentTo = new ConcurrentLinkedQueue<>();
    private final List<ExecutorService> executors = new ArrayList<>();
    private final AtomicReference<Set<String>> holdFor = new AtomicReference<>();
    private final CountDownLatch held = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    /** Records every address handed to the provider; the first call for {@link #holdFor} blocks until released (a slow provider). */
    @BeforeEach
    void recordProviderSends() {
        when(provider.sendBatch(anyList())).thenAnswer(inv -> {
            List<CampaignEmailProvider.OutgoingEmail> batch = inv.getArgument(0);
            batch.forEach(e -> sentTo.add(e.to()));
            Set<String> hold = holdFor.get();
            if (hold != null && batch.stream().anyMatch(e -> hold.contains(e.to())) && holdFor.compareAndSet(hold, null)) {
                held.countDown();
                if (!release.await(WAIT.toSeconds() * 2, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
            }
            return batch.stream().map(e -> "msg-" + UUID.randomUUID()).toList();
        });
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        try {
            shutdownExecutors();
        } finally {
            CampaignRows.delete(jdbc, orgIds);
        }
    }

    @Test
    void concurrentClaims_splitTheDueSetWithoutOverlap() throws Exception {
        Instant now = Instant.now();
        UUID orgId = awakeOrg().getId();
        List<UUID> due = new ArrayList<>();
        // More than the LIMIT of 10 passed below, ordered by scheduled_at, so a single claimer cannot take them all.
        for (int i = 0; i < 12; i++) {
            due.add(campaign(orgId, now.minus(3650, ChronoUnit.DAYS).plusSeconds(i)).getId());
        }
        List<Map<String, Object>> foreign = requireClaimRoom(orgId, now, false, false, due.size(), 20);
        CountDownLatch start = new CountDownLatch(1);
        // Both claims must return while both transactions are still open: a claimer that waits on the other's locks never arrives.
        CyclicBarrier bothHolding = new CyclicBarrier(2);
        Callable<List<UUID>> claimAndHold = () -> {
            start.await();
            // The outer tx stands in for a transactional claim; production releases these locks at statement end.
            return tx.execute(st -> {
                List<UUID> ids = campaigns.claimDue(now, now.minus(5, ChronoUnit.MINUTES), false, false, 10)
                        .stream().map(Campaign::getId).toList();
                try {
                    bothHolding.await(WAIT.toSeconds(), TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("the other claimer never returned while this one held its rows", e);
                }
                return ids;
            });
        };
        Future<List<UUID>> a = executor().submit(claimAndHold);
        Future<List<UUID>> b = executor().submit(claimAndHold);
        start.countDown();
        List<UUID> claimedA = a.get(WAIT.toSeconds() * 2, TimeUnit.SECONDS);
        List<UUID> claimedB = b.get(WAIT.toSeconds() * 2, TimeUnit.SECONDS);

        Set<UUID> overlap = new HashSet<>(claimedA);
        overlap.retainAll(claimedB);
        assertThat(overlap).as("ids claimed by both replicas, other tests' rows included").isEmpty();
        Set<UUID> union = new HashSet<>(claimedA);
        union.addAll(claimedB);
        assertThat(union).as("together the replicas claim every claimable campaign").hasSize(due.size() + foreign.size())
                .containsAll(due);
        // The claims interleave row by row, but whoever holds the latest own campaign lacks an earlier one,
        // which its ORDER BY would have returned first unless the other replica held it.
        UUID latest = due.get(due.size() - 1);
        List<UUID> holder = claimedA.contains(latest) ? claimedA : claimedB;
        assertThat(due.subList(0, due.size() - 1)).as("own campaigns skipped by the holder of the latest")
                .anyMatch(id -> !holder.contains(id));
    }

    @Test
    void twoDrainsOfOneCampaign_sendEachRecipientOnce() throws Exception {
        Campaign c = campaign(awakeOrg().getId(), Instant.now().minus(3650, ChronoUnit.DAYS));
        List<String> own = pendingRecipients(c, EmailChannelSender.BATCH_SIZE + 50);
        holdFirstSendTo(own);
        requireClaimRoom(c.getOrgId(), Instant.now(), audiencePlan.sendsEnabled(),
                audiencePlan.legalIdentityAllCampaigns(), 1, 10);

        ExecutorService replicaA = executor();
        ExecutorService replicaB = executor();
        try {
            Future<?> first = replicaA.submit(() -> dispatcher.runOnce());
            // A is inside the provider call for its first batch, its transaction open on those rows.
            awaitHeld();
            assertThat(ownSends(own)).hasSize(EmailChannelSender.BATCH_SIZE);
            assertThat(lockablePending(c)).as("rows A does not hold").isEqualTo(50);
            makeHeartbeatStale(c);
            Future<?> second = replicaB.submit(() -> dispatcher.runOnce());
            // B reclaims the stale campaign, emails only the rows A does not hold, and finishes while A is still held.
            second.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            assertThat(first.isDone()).as("replica A still holds its batch").isFalse();
            assertThat(ownSends(own)).hasSize(own.size());
            releaseHeld();
            first.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            releaseHeld();
            shutdownExecutors();
        }

        assertEachSentOnce(c, own);
    }

    @Test
    void whileOneReplicaHoldsTheDispatcherLock_anotherRunDoesNothing() throws Exception {
        Campaign c = campaign(awakeOrg().getId(), Instant.now().minus(3650, ChronoUnit.DAYS));
        List<String> own = pendingRecipients(c, EmailChannelSender.BATCH_SIZE + 50);
        holdFirstSendTo(own);
        requireClaimRoom(c.getOrgId(), Instant.now(), audiencePlan.sendsEnabled(),
                audiencePlan.legalIdentityAllCampaigns(), 1, 10);

        expireDispatcherLock();
        ExecutorService replicaA = executor();
        ExecutorService replicaB = executor();
        try {
            Future<?> first = replicaA.submit(() -> dispatcher.run());
            awaitHeld();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM shedlock WHERE name = ? AND lock_until > now()",
                    Integer.class, LOCK_NAME)).as("replica A holds the dispatcher lock").isEqualTo(1);
            // Reclaimable now, so only the lock keeps B away from the rows A has not reached.
            makeHeartbeatStale(c);
            Future<?> second = replicaB.submit(() -> dispatcher.run());
            second.get(WAIT.toSeconds(), TimeUnit.SECONDS);

            assertThat(ownSends(own)).as("replica B sent nothing").hasSize(EmailChannelSender.BATCH_SIZE);
            assertThat(first.isDone()).as("replica A still holds its batch").isFalse();
            releaseHeld();
            first.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            try {
                releaseHeld();
                shutdownExecutors();
            } finally {
                expireDispatcherLock();
            }
        }

        assertEachSentOnce(c, own);
    }

    @Test
    void aSecondRunDoesNotClaimACampaignTheFirstIsStillDriving() throws Exception {
        Campaign c = campaign(awakeOrg().getId(), Instant.now().minus(3650, ChronoUnit.DAYS));
        // Scheduled an hour before it fell due: scheduling leaves updated_at as it was.
        jdbc.update("UPDATE campaigns SET updated_at = now() - interval '1 hour' WHERE id = ?", c.getId());
        List<String> own = pendingRecipients(c, EmailChannelSender.BATCH_SIZE + 50);
        holdFirstSendTo(own);
        requireClaimRoom(c.getOrgId(), Instant.now(), audiencePlan.sendsEnabled(),
                audiencePlan.legalIdentityAllCampaigns(), 1, 10);

        ExecutorService replicaA = executor();
        ExecutorService replicaB = executor();
        try {
            Future<?> first = replicaA.submit(() -> dispatcher.runOnce());
            awaitHeld();
            Future<?> second = replicaB.submit(() -> dispatcher.runOnce());
            second.get(WAIT.toSeconds(), TimeUnit.SECONDS);

            assertThat(ownSends(own)).as("the second run sent nothing of the campaign the first is driving")
                    .hasSize(EmailChannelSender.BATCH_SIZE);
            assertThat(first.isDone()).as("replica A still holds its batch").isFalse();
            releaseHeld();
            first.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            releaseHeld();
            shutdownExecutors();
        }

        assertEachSentOnce(c, own);
    }

    @Test
    void twoDrivesMaterializingAtOnce_neitherThrows() throws Exception {
        Organization org = awakeOrg();
        List<Membership> members = List.of(member(org.getId()), member(org.getId()));
        Campaign c = campaign(org.getId(), Instant.now().minus(3650, ChronoUnit.DAYS));
        c.setSegmentId(segmentOf(org.getId(), members));
        c.setStatus("sending");
        campaigns.save(c);
        // Two drives of one campaign, each with the entity its claim loaded; a throw is what the dispatcher marks failed.
        Campaign driveA = campaigns.findById(c.getId()).orElseThrow();
        Campaign driveB = campaigns.findById(c.getId()).orElseThrow();

        try (PgFaults.Pause pause = PgFaults.pauseWrites(dataSource, "campaign_recipients", "campaign_id", c.getId())) {
            try {
                Future<?> a = executor().submit(() -> sendUnit.processOne(driveA));
                pause.awaitBlocked(WAIT);
                Future<?> b = executor().submit(() -> sendUnit.processOne(driveB));
                // B waits on the materialize row lock, or (without it) on the same paused insert as A.
                awaitWaiting("query ILIKE '%campaigns%for no key update%'", 1, "count(*) FILTER (WHERE wait_event_type = 'Lock' "
                        + "AND query ILIKE '%insert into campaign_recipients%') >= 2");
                pause.release();
                a.get(WAIT.toSeconds(), TimeUnit.SECONDS);
                b.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            } finally {
                pause.release();
                shutdownExecutors();
            }
        }

        List<String> own = members.stream().map(this::emailOf).toList();
        assertThat(jdbc.queryForList("SELECT membership_id FROM campaign_recipients WHERE campaign_id = ?",
                UUID.class, c.getId())).containsExactlyInAnyOrderElementsOf(
                members.stream().map(Membership::getMembershipId).toList());
        assertEachSentOnce(c, own);
        assertThat(jdbc.queryForObject("SELECT attempts FROM campaigns WHERE id = ?", Integer.class, c.getId()))
                .isZero();
    }

    @Test
    void aCampaignTheFiltersHold_keepsItsStateThroughARun() {
        Organization o = fx.org();
        // Local time near 02:00 right now: inside email quiet hours.
        o.setTimezone(ZoneOffset.ofHours(Math.floorMod(2 - Instant.now().atZone(ZoneOffset.UTC).getHour() + 12, 24) - 12).getId());
        o = orgs.save(o);
        orgIds.add(o.getId());
        Campaign c = campaign(o.getId(), Instant.now().minus(3650, ChronoUnit.DAYS));
        jdbc.update("UPDATE campaigns SET status = 'failed', attempts = 1, updated_at = now() - interval '1 hour' WHERE id = ?",
                c.getId());
        Map<String, Object> before = stateOf(c);

        dispatcher.runOnce();

        assertThat(stateOf(c)).isEqualTo(before);
    }

    @Test
    void aRunPastItsBudget_stopsTheDriveAndReleasesWhatItNeverStarted() {
        UUID orgId = awakeOrg().getId();
        Instant ancient = Instant.now().minus(3650, ChronoUnit.DAYS);
        Campaign driven = campaign(orgId, ancient);
        List<String> own = pendingRecipients(driven, EmailChannelSender.BATCH_SIZE + 50);
        // Claimed after `driven`; its prior state is 'failed', not the initial 'scheduled'.
        Campaign unstarted = campaign(orgId, ancient.plusSeconds(1));
        jdbc.update("UPDATE campaigns SET status = 'failed', attempts = 1, updated_at = now() - interval '1 hour' WHERE id = ?",
                unstarted.getId());
        Map<String, Object> unstartedBefore = stateOf(unstarted);
        // Claimed too, then given a new status by a writer that leaves updated_at alone (defensive: no such writer moves a sending row today).
        Campaign changed = campaign(orgId, ancient.plusSeconds(2));
        // Claimed too, then heartbeated by another drive while its status stays 'sending'.
        Campaign heartbeat = campaign(orgId, ancient.plusSeconds(3));
        requireClaimRoom(orgId, Instant.now(), audiencePlan.sendsEnabled(), audiencePlan.legalIdentityAllCampaigns(), 4, 10);
        when(provider.sendBatch(anyList())).thenAnswer(inv -> {
            List<CampaignEmailProvider.OutgoingEmail> batch = inv.getArgument(0);
            batch.forEach(e -> sentTo.add(e.to()));
            // The first batch outlasts the run budget, and meanwhile other writers touch two claimed campaigns.
            clock.advance(CampaignDispatcher.RUN_BUDGET.plusSeconds(1));
            elsewhere(() -> jdbc.update("UPDATE campaigns SET status = 'canceled' WHERE id = ?", changed.getId()));
            elsewhere(() -> campaigns.touch(heartbeat.getId(), Instant.now()));
            return batch.stream().map(e -> "msg-" + UUID.randomUUID()).toList();
        });

        dispatcher.runOnce();

        assertThat(ownSends(own)).as("one batch, then the drive stops").hasSize(EmailChannelSender.BATCH_SIZE);
        assertThat(jdbc.queryForObject("SELECT status FROM campaigns WHERE id = ?", String.class, driven.getId()))
                .isEqualTo("sending");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign_recipients WHERE campaign_id = ? AND status = 'pending'",
                Integer.class, driven.getId())).isEqualTo(50);
        assertThat(stateOf(unstarted)).as("the unstarted claim is back to its prior state").isEqualTo(unstartedBefore);
        assertThat(jdbc.queryForObject("SELECT status FROM campaigns WHERE id = ?", String.class, changed.getId()))
                .as("the release keeps another writer's status").isEqualTo("canceled");
        assertThat(jdbc.queryForObject("SELECT status FROM campaigns WHERE id = ?", String.class, heartbeat.getId()))
                .as("the release leaves a campaign another drive heartbeats").isEqualTo("sending");
    }

    @Test
    void aCampaignCanceledBeforeItsDriveThrows_keepsItsStatusAndAttempts() {
        Campaign c = campaign(awakeOrg().getId(), Instant.now().minus(3650, ChronoUnit.DAYS));
        pendingRecipients(c, 2);
        requireClaimRoom(c.getOrgId(), Instant.now(), audiencePlan.sendsEnabled(),
                audiencePlan.legalIdentityAllCampaigns(), 1, 10);
        when(provider.sendBatch(anyList())).thenAnswer(inv -> {
            elsewhere(() -> jdbc.update("UPDATE campaigns SET status = 'canceled', updated_at = now() WHERE id = ?", c.getId()));
            throw new IllegalStateException("provider client blew up");
        });

        dispatcher.runOnce();

        Map<String, Object> row = stateOf(c);
        assertThat(row.get("status")).isEqualTo("canceled");
        assertThat(((Number) row.get("attempts")).intValue()).as("a failure the cancel overtook is not counted").isZero();
        assertThat(row.get("last_error")).isNull();
    }

    @Test
    void aDirectDriveOfACopyWhoseStatusMoved_materializesAndSendsNothing() {
        Organization org = awakeOrg();
        Campaign c = campaign(org.getId(), Instant.now().plus(1, ChronoUnit.DAYS));
        c.setSegmentId(segmentOf(org.getId(), List.of(member(org.getId()))));
        campaigns.save(c);
        // The caller's copy still says 'scheduled'; the row was canceled since.
        Campaign copy = campaigns.findById(c.getId()).orElseThrow();
        jdbc.update("UPDATE campaigns SET status = 'canceled', updated_at = now() WHERE id = ?", c.getId());

        sendUnit.processOne(copy);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign_recipients WHERE campaign_id = ?",
                Integer.class, c.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM campaigns WHERE id = ?", String.class, c.getId()))
                .isEqualTo("canceled");
    }

    @Test
    void aCampaignThatCannotBeMarkedFailed_doesNotStopTheRestOfTheClaim() {
        UUID orgId = awakeOrg().getId();
        Instant ancient = Instant.now().minus(3650, ChronoUnit.DAYS);
        Campaign broken = campaign(orgId, ancient);
        Set<String> brokenTo = new HashSet<>(pendingRecipients(broken, 1));
        Campaign next = campaign(orgId, ancient.plusSeconds(1));
        List<String> own = pendingRecipients(next, 2);
        requireClaimRoom(orgId, Instant.now(), audiencePlan.sendsEnabled(), audiencePlan.legalIdentityAllCampaigns(), 2, 10);
        AtomicReference<PgFaults.Fault> fault = new AtomicReference<>();
        when(provider.sendBatch(anyList())).thenAnswer(inv -> {
            List<CampaignEmailProvider.OutgoingEmail> batch = inv.getArgument(0);
            if (batch.stream().anyMatch(e -> brokenTo.contains(e.to()))) {
                // The drive throws, and the write that would mark it failed is rejected too.
                elsewhere(() -> fault.set(PgFaults.failWrites(jdbc, "campaigns", "id", broken.getId())));
                throw new IllegalStateException("provider client blew up");
            }
            batch.forEach(e -> sentTo.add(e.to()));
            return batch.stream().map(e -> "msg-" + UUID.randomUUID()).toList();
        });

        try {
            dispatcher.runOnce();
        } finally {
            PgFaults.Fault f = fault.get();
            if (f != null) f.close();
        }

        assertThat(fault.get()).as("the broken campaign was driven first").isNotNull();
        assertEachSentOnce(next, own);
        assertThat(jdbc.queryForObject("SELECT status FROM campaigns WHERE id = ?", String.class, broken.getId()))
                .isEqualTo("sending");
    }

    @Test
    void aClaimThatCannotBeReleased_doesNotKeepTheNextOneFromBeingReleased() {
        UUID orgId = awakeOrg().getId();
        Instant ancient = Instant.now().minus(3650, ChronoUnit.DAYS);
        Campaign driven = campaign(orgId, ancient);
        pendingRecipients(driven, 1);
        Campaign stuck = campaign(orgId, ancient.plusSeconds(1));
        Campaign released = campaign(orgId, ancient.plusSeconds(2));
        jdbc.update("UPDATE campaigns SET status = 'failed', attempts = 1, updated_at = now() - interval '1 hour' WHERE id = ?",
                released.getId());
        Map<String, Object> releasedBefore = stateOf(released);
        requireClaimRoom(orgId, Instant.now(), audiencePlan.sendsEnabled(), audiencePlan.legalIdentityAllCampaigns(), 3, 10);
        AtomicReference<PgFaults.Fault> fault = new AtomicReference<>();
        when(provider.sendBatch(anyList())).thenAnswer(inv -> {
            List<CampaignEmailProvider.OutgoingEmail> batch = inv.getArgument(0);
            // The first batch outlasts the run budget, and the release of the first unstarted claim will be rejected.
            clock.advance(CampaignDispatcher.RUN_BUDGET.plusSeconds(1));
            elsewhere(() -> fault.set(PgFaults.failWrites(jdbc, "campaigns", "id", stuck.getId())));
            return batch.stream().map(e -> "msg-" + UUID.randomUUID()).toList();
        });

        try {
            dispatcher.runOnce();
        } finally {
            PgFaults.Fault f = fault.get();
            if (f != null) f.close();
        }

        assertThat(fault.get()).as("the driven campaign reached the provider").isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM campaigns WHERE id = ?", String.class, stuck.getId()))
                .as("its release was rejected").isEqualTo("sending");
        assertThat(stateOf(released)).as("the next unstarted claim is still released").isEqualTo(releasedBefore);
    }

    @Test
    void moreEligibleCampaignsThanOneClaim_claimsTheFirstTenAndLeavesTheRest() {
        UUID orgId = awakeOrg().getId();
        Instant ancient = Instant.now().minus(3650, ChronoUnit.DAYS);
        List<Campaign> due = new ArrayList<>();
        for (int i = 0; i <= CampaignDispatcher.CLAIM_LIMIT; i++) {
            Campaign c = campaign(orgId, ancient.plusSeconds(i));
            pendingRecipients(c, 1);
            due.add(c);
        }
        Campaign last = due.get(CampaignDispatcher.CLAIM_LIMIT);
        Map<String, Object> lastBefore = stateOf(last);
        requireClaimRoom(orgId, Instant.now(), audiencePlan.sendsEnabled(), audiencePlan.legalIdentityAllCampaigns(),
                CampaignDispatcher.CLAIM_LIMIT, CampaignDispatcher.CLAIM_LIMIT);

        dispatcher.runOnce();

        assertThat(due.subList(0, CampaignDispatcher.CLAIM_LIMIT))
                .as("the first ten in queue order are claimed and sent")
                .allSatisfy(c -> assertThat(jdbc.queryForObject("SELECT status FROM campaigns WHERE id = ?",
                        String.class, c.getId())).isEqualTo("sent"));
        assertThat(stateOf(last)).as("the eleventh is left for the next run").isEqualTo(lastBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign_recipients WHERE campaign_id = ? AND status = 'pending'",
                Integer.class, last.getId())).isEqualTo(1);
    }

    enum Hold { PAUSED, ORG_GONE, QUIET_HOURS }

    @ParameterizedTest
    @EnumSource(Hold.class)
    void heldCampaignsFirstInTheQueue_doNotStarveAnotherOrgsCampaign(Hold hold) {
        Instant ancient = Instant.now().minus(3650, ChronoUnit.DAYS);
        UUID heldOrg = heldOrg(hold);
        List<Campaign> held = new ArrayList<>();
        // All ordered before the eligible campaign. SQL-filtered holds outnumber the scan, so a Java-side filter goes red.
        int heldRows = hold == Hold.QUIET_HOURS ? 12 : CampaignDispatcher.SCAN_LIMIT + 1;
        for (int i = 0; i < heldRows; i++) held.add(campaign(heldOrg, ancient.plusSeconds(i)));
        jdbc.update("UPDATE campaigns SET status = 'failed', attempts = 1, updated_at = now() - interval '1 hour' WHERE id = ?",
                held.get(0).getId());
        List<Map<String, Object>> heldBefore = held.stream().map(this::stateOf).toList();
        UUID orgId = awakeOrg().getId();
        Campaign eligible = campaign(orgId, ancient.plusSeconds(heldRows + 60));
        List<String> own = pendingRecipients(eligible, 2);
        requireClaimRoom(orgId, heldOrg, Instant.now(), audiencePlan.sendsEnabled(), audiencePlan.legalIdentityAllCampaigns(),
                1, 10);

        dispatcher.runOnce();

        assertEachSentOnce(eligible, own);
        assertThat(held.stream().map(this::stateOf).toList()).as("held campaigns are never written").isEqualTo(heldBefore);
    }

    private UUID heldOrg(Hold hold) {
        switch (hold) {
            case ORG_GONE -> {
                UUID gone = UUID.randomUUID();
                orgIds.add(gone);
                return gone;
            }
            case PAUSED -> {
                Organization o = awakeOrg();
                o.setMarketingPausedAt(Instant.now().minus(1, ChronoUnit.HOURS));
                return orgs.save(o).getId();
            }
            default -> {
                Organization o = fx.org();
                // Local time near 02:00 right now: inside email quiet hours.
                o.setTimezone(ZoneOffset.ofHours(Math.floorMod(2 - Instant.now().atZone(ZoneOffset.UTC).getHour() + 12, 24) - 12).getId());
                o = orgs.save(o);
                orgIds.add(o.getId());
                return o.getId();
            }
        }
    }

    @Test
    void aRetryRacingTheClaim_answers409AndTheCampaignSends() throws Exception {
        Organization org = awakeOrg();
        AuthPrincipal owner = fx.principal(fx.owner(org));
        Campaign c = campaign(org.getId(), Instant.now().minus(3650, ChronoUnit.DAYS));
        jdbc.update("UPDATE campaigns SET status = 'failed', attempts = 1 WHERE id = ?", c.getId());
        List<String> own = pendingRecipients(c, 2);
        requireClaimRoom(org.getId(), Instant.now(), audiencePlan.sendsEnabled(),
                audiencePlan.legalIdentityAllCampaigns(), 1, 10);

        Throwable refused;
        try (PgFaults.Pause pause = PgFaults.pauseWrites(dataSource, "campaigns", "id", c.getId())) {
            try {
                Future<?> run = executor().submit(() -> dispatcher.runOnce());
                // The claim holds the row lock; its flip to 'sending' is paused before commit.
                pause.awaitBlocked(WAIT);
                String tag = "organizer-" + UUID.randomUUID().toString().substring(0, 8);
                Future<?> organizer = executor().submit(() -> tx.executeWithoutResult(st -> {
                    // Tags this backend so the wait below sees the organizer's own UPDATE blocked, nothing else.
                    jdbc.execute("SET LOCAL application_name = '" + tag + "'");
                    campaignService.retry(owner, c.getId());
                }));
                awaitWaiting("application_name = '" + tag + "'", 1, "FALSE");
                pause.release();
                run.get(WAIT.toSeconds(), TimeUnit.SECONDS);
                refused = catchThrowable(() -> organizer.get(WAIT.toSeconds(), TimeUnit.SECONDS));
            } finally {
                pause.release();
                shutdownExecutors();
            }
        }

        assertThat(refused).as("the organizer is told the campaign already left").isInstanceOf(ExecutionException.class)
                .cause().isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        assertEachSentOnce(c, own);
    }

    @Test
    void aCancelRacingTheClaim_stopsTheCampaignBeforeItsFirstBatch() throws Exception {
        Organization org = awakeOrg();
        AuthPrincipal owner = fx.principal(fx.owner(org));
        Campaign c = campaign(org.getId(), Instant.now().minus(3650, ChronoUnit.DAYS));
        List<String> own = pendingRecipients(c, 2);
        requireClaimRoom(org.getId(), Instant.now(), audiencePlan.sendsEnabled(),
                audiencePlan.legalIdentityAllCampaigns(), 1, 10);

        Throwable refused;
        try (PgFaults.Pause pause = PgFaults.pauseWrites(dataSource, "campaigns", "id", c.getId())) {
            try {
                Future<?> run = executor().submit(() -> dispatcher.runOnce());
                pause.awaitBlocked(WAIT);
                // The cancel's compare-and-set waits on the claim's row lock, queued ahead of the drive's materialize lock.
                Future<?> cancel = executor().submit(() -> campaignService.cancel(owner, c.getId()));
                awaitWaitingOrDone(cancel, "query ILIKE 'update campaigns%canceled%'");
                pause.release();
                run.get(WAIT.toSeconds(), TimeUnit.SECONDS);
                refused = catchThrowable(() -> cancel.get(WAIT.toSeconds(), TimeUnit.SECONDS));
            } finally {
                pause.release();
                shutdownExecutors();
            }
        }

        assertThat(refused).as("a cancel that lands on a campaign the claim just took still stops it").isNull();
        assertThat(ownSends(own)).as("nothing left before the cancel").isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM campaigns WHERE id = ?", String.class, c.getId()))
                .isEqualTo("canceled");
        assertThat(recipientStates(c)).containsOnly("skipped/campaign_canceled").hasSize(2);
        assertThat(campaignService.get(owner, c.getId()).revMinor()).as("no link of it in any inbox").isNull();
        assertThat(listed(owner, c).revMinor()).isNull();
    }

    @Test
    void stoppingACampaignMidDrive_sendsNoFurtherBatchAndSkipsTheRest() throws Exception {
        Organization org = awakeOrg();
        AuthPrincipal owner = fx.principal(fx.owner(org));
        Campaign c = campaign(org.getId(), Instant.now().minus(3650, ChronoUnit.DAYS));
        List<String> own = pendingRecipients(c, EmailChannelSender.BATCH_SIZE + 50);
        requireClaimRoom(org.getId(), Instant.now(), audiencePlan.sendsEnabled(),
                audiencePlan.legalIdentityAllCampaigns(), 1, 10);
        AtomicReference<Future<?>> stop = new AtomicReference<>();
        // The organizer stops the campaign while the first batch is at the provider.
        when(provider.sendBatch(anyList())).thenAnswer(inv -> {
            List<CampaignEmailProvider.OutgoingEmail> batch = inv.getArgument(0);
            batch.forEach(e -> sentTo.add(e.to()));
            if (stop.get() == null) {
                stop.set(executor().submit(() -> campaignService.cancel(owner, c.getId())));
                // Committed the status, now waiting on this batch's locked rows. Polled on its own connection: inside
                // this batch's transaction pg_stat_activity is one frozen snapshot.
                Future<?> stopping = stop.get();
                executor().submit(() -> {
                    awaitWaitingOrDone(stopping, "query ILIKE 'update campaign_recipients set status = ''skipped''%'");
                    return null;
                }).get(WAIT.toSeconds() * 2, TimeUnit.SECONDS);
            }
            return batch.stream().map(e -> "msg-" + UUID.randomUUID()).toList();
        });

        dispatcher.runOnce();
        Throwable refused = catchThrowable(() -> stop.get().get(WAIT.toSeconds(), TimeUnit.SECONDS));

        assertThat(refused).as("a sending campaign can be stopped").isNull();
        assertThat(ownSends(own)).as("only the batch already out").hasSize(EmailChannelSender.BATCH_SIZE);
        assertThat(jdbc.queryForObject("SELECT status FROM campaigns WHERE id = ?", String.class, c.getId()))
                .isEqualTo("canceled");
        Map<String, Long> states = new java.util.TreeMap<>();
        recipientStates(c).forEach(s -> states.merge(s, 1L, Long::sum));
        assertThat(states).isEqualTo(Map.of("sent/null", 100L, "skipped/campaign_canceled", 50L));
        assertThat(campaignService.detailWithStats(owner, c.getId()).stats().sent()).isEqualTo(100);
        com.imin.iminapi.marketing.dto.RecipientCounts counts =
                campaignService.listRecipients(c.getId(), owner, null, null, 0, 10).counts();
        assertThat(counts.total()).isEqualTo(150);
        assertThat(counts.skipped()).isEqualTo(50);
        assertThat(auditRows.assertRecorded(org.getId(), "CAMPAIGN_CANCELED", "campaign", c.getId()).getSummary())
                .isEqualTo("Campaign canceled: 100 sent, 50 not sent");
        // Its links are in 100 inboxes, so attributed revenue is a real answer, not "never sent".
        assertThat(campaignService.get(owner, c.getId()).revMinor()).isEqualTo(0L);
        assertThat(listed(owner, c).revMinor()).isEqualTo(0L);

        // A stopped campaign is never reclaimed, however stale its heartbeat.
        makeHeartbeatStale(c);
        dispatcher.runOnce();
        assertThat(ownSends(own)).hasSize(EmailChannelSender.BATCH_SIZE);
        assertThat(jdbc.queryForObject("SELECT status FROM campaigns WHERE id = ?", String.class, c.getId()))
                .isEqualTo("canceled");
    }

    @Test
    void aCampaignCanceledMidDrive_sendsNoFurtherBatchAndStaysCanceled() {
        Campaign c = campaign(awakeOrg().getId(), Instant.now().minus(3650, ChronoUnit.DAYS));
        List<String> own = pendingRecipients(c, EmailChannelSender.BATCH_SIZE + 50);
        // Out of attempts and never claimed again; only finish() would retire it to 'failed'.
        UUID exhausted = UUID.randomUUID();
        jdbc.update("INSERT INTO campaign_recipients (id, campaign_id, email, status, attempt_count, last_event_at) "
                + "VALUES (?, ?, ?, 'pending', 3, now())", exhausted, c.getId(), fx.email("exhausted"));
        requireClaimRoom(c.getOrgId(), Instant.now(), audiencePlan.sendsEnabled(),
                audiencePlan.legalIdentityAllCampaigns(), 1, 10);
        // Any writer that stops the campaign while a batch is out (a raw write: no cancel skips the rows).
        when(provider.sendBatch(anyList())).thenAnswer(inv -> {
            List<CampaignEmailProvider.OutgoingEmail> batch = inv.getArgument(0);
            batch.forEach(e -> sentTo.add(e.to()));
            elsewhere(() -> jdbc.update("UPDATE campaigns SET status = 'canceled', updated_at = now() WHERE id = ?", c.getId()));
            return batch.stream().map(e -> "msg-" + UUID.randomUUID()).toList();
        });

        dispatcher.runOnce();

        assertThat(ownSends(own)).as("only the batch already out").hasSize(EmailChannelSender.BATCH_SIZE);
        assertThat(jdbc.queryForObject("SELECT status FROM campaigns WHERE id = ?", String.class, c.getId()))
                .isEqualTo("canceled");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign_recipients WHERE campaign_id = ? AND status = 'pending' "
                + "AND id <> ?", Integer.class, c.getId(), exhausted)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT status FROM campaign_recipients WHERE id = ?", String.class, exhausted))
                .as("a stopped drive never reaches finish()").isEqualTo("pending");
    }

    @Test
    void aCampaignCanceledDuringItsLastBatch_isNeitherMarkedSentNorAnnounced() {
        Organization org = awakeOrg();
        Campaign c = campaign(org.getId(), Instant.now().minus(3650, ChronoUnit.DAYS));
        UUID eventId = fx.event(org, fx.owner(org), EventStatus.LIVE, Instant.now().plus(30, ChronoUnit.DAYS)).getId();
        c.setEventId(eventId);
        campaigns.save(c);
        // Exactly one batch: the drain ends without another status re-read, so finish() runs.
        List<String> own = pendingRecipients(c, EmailChannelSender.BATCH_SIZE);
        requireClaimRoom(org.getId(), Instant.now(), audiencePlan.sendsEnabled(),
                audiencePlan.legalIdentityAllCampaigns(), 1, 10);
        when(provider.sendBatch(anyList())).thenAnswer(inv -> {
            List<CampaignEmailProvider.OutgoingEmail> batch = inv.getArgument(0);
            batch.forEach(e -> sentTo.add(e.to()));
            elsewhere(() -> jdbc.update("UPDATE campaigns SET status = 'canceled', updated_at = now() WHERE id = ?", c.getId()));
            return batch.stream().map(e -> "msg-" + UUID.randomUUID()).toList();
        });

        dispatcher.runOnce();

        assertThat(ownSends(own)).hasSize(EmailChannelSender.BATCH_SIZE);
        Map<String, Object> row = jdbc.queryForMap("SELECT status, sent_at FROM campaigns WHERE id = ?", c.getId());
        SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(published.stream(PredictorMarketingEvents.CampaignSent.class)
                    .filter(e -> eventId.equals(e.eventId()))).as("re-forecast for a canceled campaign").isEmpty();
            soft.assertThat(row.get("status")).isEqualTo("canceled");
            soft.assertThat(row.get("sent_at")).isNull();
        });
    }

    @Test
    void materializingAStaleCopy_keepsTheStoredStatus() {
        Organization org = awakeOrg();
        List<Membership> members = List.of(member(org.getId()), member(org.getId()));
        Campaign c = campaign(org.getId(), Instant.now().minus(3650, ChronoUnit.DAYS));
        c.setSegmentId(segmentOf(org.getId(), members));
        c.setStatus("sending");
        campaigns.save(c);
        // The drive's copy says 'sending'; the row is canceled before it materializes.
        Campaign driveCopy = campaigns.findById(c.getId()).orElseThrow();
        jdbc.update("UPDATE campaigns SET status = 'canceled', updated_at = now() WHERE id = ?", c.getId());

        materializer.materialize(driveCopy);

        Map<String, Object> row = jdbc.queryForMap("SELECT status, recipient_count FROM campaigns WHERE id = ?", c.getId());
        assertThat(row.get("status")).isEqualTo("canceled");
        assertThat(((Number) row.get("recipient_count")).intValue()).as("the snapshot counts are still recorded").isEqualTo(2);
    }

    @Test
    void materializingACanceledCampaign_queuesNoRecipient() {
        Organization org = awakeOrg();
        List<Membership> members = List.of(member(org.getId()), member(org.getId()));
        Campaign c = campaign(org.getId(), Instant.now().minus(3650, ChronoUnit.DAYS));
        c.setSegmentId(segmentOf(org.getId(), members));
        c.setStatus("sending");
        campaigns.save(c);
        // Canceled between the claim and materialize's row lock: no cancel ran after the rows exist.
        Campaign driveCopy = campaigns.findById(c.getId()).orElseThrow();
        jdbc.update("UPDATE campaigns SET status = 'canceled', updated_at = now() WHERE id = ?", c.getId());

        materializer.materialize(driveCopy);

        assertThat(recipientStates(c)).containsOnly("skipped/campaign_canceled").hasSize(2);
        assertThat(jdbc.queryForObject("SELECT recipient_count FROM campaigns WHERE id = ?", Integer.class, c.getId()))
                .as("the audience the campaign was stopped against").isEqualTo(2);
    }

    /**
     * Other tests' claimable campaigns (the claimDue predicate, without LIMIT or lock) that would take claim slots;
     * fails naming them when they leave fewer than {@code ownRows} of {@code capacity} for this test.
     */
    private List<Map<String, Object>> requireClaimRoom(UUID ownOrg, Instant now, boolean audiencePlanSendsEnabled,
                                                       boolean legalIdentityAllCampaigns, int ownRows, int capacity) {
        return requireClaimRoom(ownOrg, ownOrg, now, audiencePlanSendsEnabled, legalIdentityAllCampaigns, ownRows, capacity);
    }

    private List<Map<String, Object>> requireClaimRoom(UUID ownOrg, UUID otherOwnOrg, Instant now,
                                                       boolean audiencePlanSendsEnabled, boolean legalIdentityAllCampaigns,
                                                       int ownRows, int capacity) {
        List<Map<String, Object>> foreign = jdbc.queryForList("""
                SELECT id, org_id, status, scheduled_at, updated_at, attempts, name, origin FROM campaigns
                WHERE channel = 'email'
                  AND ((status = 'scheduled' AND scheduled_at <= ?)
                    OR (status = 'failed' AND attempts < 3)
                    OR (status = 'sending' AND updated_at < ?))
                  AND (? = TRUE OR origin <> 'audience_plan')
                  AND ((origin <> 'audience_plan' AND ? = FALSE) OR EXISTS (
                        SELECT 1 FROM organizations o WHERE o.id = campaigns.org_id
                          AND TRIM(COALESCE(o.legal_name, '')) <> ''
                          AND TRIM(COALESCE(o.legal_contact, '')) <> ''))
                  AND org_id NOT IN (?, ?)
                """, Timestamp.from(now), Timestamp.from(now.minus(5, ChronoUnit.MINUTES)),
                audiencePlanSendsEnabled, legalIdentityAllCampaigns, ownOrg, otherOwnOrg);
        assertThat(foreign).as("claimable campaigns another test left behind (CampaignRows cleanup rule)")
                .hasSizeLessThanOrEqualTo(capacity - ownRows);
        return foreign;
    }

    private void assertEachSentOnce(Campaign c, List<String> own) {
        assertThat(ownSends(own)).hasSize(own.size()).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(own);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, attempt_count, provider_message_id FROM campaign_recipients WHERE campaign_id = ?",
                c.getId());
        assertThat(rows).hasSize(own.size())
                .allSatisfy(r -> {
                    assertThat(r.get("status")).isEqualTo("sent");
                    assertThat(((Number) r.get("attempt_count")).intValue()).isEqualTo(1);
                    assertThat(r.get("provider_message_id")).isNotNull();
                });
        assertThat(rows.stream().map(r -> r.get("provider_message_id")).toList()).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("SELECT status FROM campaigns WHERE id = ?", String.class, c.getId()))
                .isEqualTo("sent");
    }

    private void holdFirstSendTo(List<String> own) {
        holdFor.set(new HashSet<>(own));
    }

    private void awaitHeld() throws InterruptedException {
        if (!held.await(WAIT.toSeconds(), TimeUnit.SECONDS)) throw new AssertionError("replica A never reached the provider");
    }

    private void releaseHeld() {
        release.countDown();
    }

    /** Pending rows of the campaign that no open transaction holds. */
    private int lockablePending(Campaign c) {
        return tx.execute(st -> jdbc.queryForList("SELECT id FROM campaign_recipients WHERE campaign_id = ? "
                + "AND status = 'pending' FOR UPDATE SKIP LOCKED", c.getId()).size());
    }

    private List<String> ownSends(List<String> own) {
        Set<String> mine = new HashSet<>(own);
        return sentTo.stream().filter(mine::contains).toList();
    }

    /** runOnce reads Instant.now(): an offset that puts the org's local time near noon right now. */
    private Organization awakeOrg() {
        Organization o = fx.org();
        o.setTimezone(ZoneOffset.ofHours(12 - Instant.now().atZone(ZoneOffset.UTC).getHour()).getId());
        o = orgs.save(o);
        orgIds.add(o.getId());
        return o;
    }

    private Campaign campaign(UUID orgId, Instant scheduledAt) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Concurrent");
        c.setStatus("scheduled");
        c.setScheduledAt(scheduledAt);
        c.setSubject("S");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    /** Pre-materialised pending rows, so both replicas drain the same queue and the materializer no-ops. */
    private List<String> pendingRecipients(Campaign c, int n) {
        List<String> emails = new ArrayList<>(n);
        List<Object[]> args = new ArrayList<>(n);
        Timestamp now = Timestamp.from(Instant.now());
        for (int i = 0; i < n; i++) {
            String email = fx.email("conc" + i);
            emails.add(email);
            args.add(new Object[]{UUID.randomUUID(), c.getId(), email, now});
        }
        jdbc.batchUpdate("INSERT INTO campaign_recipients (id, campaign_id, email, status, last_event_at) "
                + "VALUES (?, ?, ?, 'pending', ?)", args);
        return emails;
    }

    /**
     * Runs a write on its own thread and connection: called from a provider answer, the test's JdbcTemplate would
     * otherwise join the batch transaction and commit or roll back with it.
     */
    private void elsewhere(Runnable write) throws Exception {
        executor().submit(write).get(WAIT.toSeconds(), TimeUnit.SECONDS);
    }

    private Map<String, Object> stateOf(Campaign c) {
        return jdbc.queryForMap("SELECT status, attempts, updated_at, last_error FROM campaigns WHERE id = ?", c.getId());
    }

    /** The campaign's row in the organizer's campaign list. */
    private com.imin.iminapi.marketing.dto.CampaignSummary listed(AuthPrincipal owner, Campaign c) {
        return campaignService.list(owner, null, null, 0, 50).stream()
                .filter(s -> s.id().equals(c.getId())).findFirst().orElseThrow();
    }

    /** Each recipient row of the campaign as {@code status/skip_reason}. */
    private List<String> recipientStates(Campaign c) {
        return jdbc.queryForList("SELECT status || '/' || COALESCE(skip_reason, 'null') FROM campaign_recipients "
                + "WHERE campaign_id = ?", String.class, c.getId());
    }

    /** Waits until a backend matching {@code where} is blocked on a lock, or {@code action} has already returned. */
    private void awaitWaitingOrDone(Future<?> action, String where) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        String sql = "SELECT count(*) FILTER (WHERE wait_event_type = 'Lock' AND " + where + ") >= 1"
                + " FROM pg_stat_activity WHERE datname = current_database()";
        while (System.nanoTime() < deadline) {
            if (action.isDone() || Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class))) return;
            Thread.sleep(10);
        }
        throw new AssertionError("no backend blocked where " + where + " within " + WAIT);
    }

    /**
     * Waits until at least {@code n} backends matching {@code where} are blocked on a lock, or {@code orElse}
     * (an aggregate over this database's backends) holds.
     */
    private void awaitWaiting(String where, int n, String orElse) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        String sql = "SELECT count(*) FILTER (WHERE wait_event_type = 'Lock' AND " + where + ") >= " + n
                + " OR " + orElse + " FROM pg_stat_activity WHERE datname = current_database()";
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class))) return;
            Thread.sleep(10);
        }
        throw new AssertionError("no backend blocked where " + where + " within " + WAIT);
    }

    /** SendGate-sendable member (explicit basis, subscribed) with its own address. */
    private Membership member(UUID orgId) {
        Consumer cn = new Consumer();
        cn.setNormalizedEmail(fx.email("mat"));
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        return memberships.save(m);
    }

    private String emailOf(Membership m) {
        return jdbc.queryForObject("SELECT normalized_email FROM consumers WHERE consumer_id = ?",
                String.class, m.getConsumerId());
    }

    private UUID segmentOf(UUID orgId, List<Membership> members) {
        Segment seg = new Segment();
        seg.setOrgId(orgId);
        seg.setName("Concurrent " + UUID.randomUUID());
        seg.setKind("static");
        seg.setSnapshotIds(members.stream().map(m -> "\"" + m.getMembershipId() + "\"").toList().toString());
        return segments.save(seg).getId();
    }

    /** The heartbeat a batch that hangs past five minutes leaves behind, which makes the campaign reclaimable. */
    private void makeHeartbeatStale(Campaign c) {
        jdbc.update("UPDATE campaigns SET updated_at = now() - interval '10 minutes' WHERE id = ?", c.getId());
    }

    private void expireDispatcherLock() {
        jdbc.update("UPDATE shedlock SET lock_until = locked_at WHERE name = ?", LOCK_NAME);
    }

    private ExecutorService executor() {
        ExecutorService e = Executors.newSingleThreadExecutor();
        executors.add(e);
        return e;
    }

    private void shutdownExecutors() throws InterruptedException {
        for (ExecutorService e : executors) e.shutdownNow();
        for (ExecutorService e : executors) {
            if (!e.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("a replica thread did not stop");
            }
        }
        executors.clear();
    }
}

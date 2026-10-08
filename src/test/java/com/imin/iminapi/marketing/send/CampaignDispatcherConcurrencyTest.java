package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/** Two dispatcher replicas at once on real Postgres: one claimer per campaign, one email per recipient. */
@IminIntegrationTest
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
        // One more than the claim's LIMIT, ordered by scheduled_at, so a single claimer cannot take them all.
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
                List<UUID> ids = campaigns.claimDue(now, now.minus(5, ChronoUnit.MINUTES), false, false)
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

    /**
     * Other tests' claimable campaigns (the claimDue predicate, without LIMIT or lock) that would take claim slots;
     * fails naming them when they leave fewer than {@code ownRows} of {@code capacity} for this test.
     */
    private List<Map<String, Object>> requireClaimRoom(UUID ownOrg, Instant now, boolean audiencePlanSendsEnabled,
                                                       boolean legalIdentityAllCampaigns, int ownRows, int capacity) {
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
                  AND org_id <> ?
                """, Timestamp.from(now), Timestamp.from(now.minus(5, ChronoUnit.MINUTES)),
                audiencePlanSendsEnabled, legalIdentityAllCampaigns, ownOrg);
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

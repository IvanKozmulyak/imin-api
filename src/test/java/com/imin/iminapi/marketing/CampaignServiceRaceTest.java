package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.dto.CampaignRequests.PatchCampaignRequest;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Organizer writes racing another writer of the same campaign row, on real Postgres row locks. */
@IminIntegrationTest
class CampaignServiceRaceTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired CampaignService service;
    @Autowired CampaignRepository campaigns;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired DataSource dataSource;

    private final List<UUID> orgIds = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void cleanUp() throws InterruptedException {
        try {
            for (ExecutorService e : executors) e.shutdownNow();
            for (ExecutorService e : executors) {
                if (!e.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS)) throw new AssertionError("a thread did not stop");
            }
        } finally {
            CampaignRows.delete(jdbc, orgIds);
        }
    }

    @Test
    void aPatchRacingASend_neverWritesDraftOverScheduled() throws Exception {
        Organization org = fx.org();
        orgIds.add(org.getId());
        AuthPrincipal owner = fx.principal(fx.owner(org));
        Campaign c = draft(org.getId());
        // Far enough out that the dispatcher never claims it during the test.
        Instant sendAt = Instant.now().plus(1, ChronoUnit.DAYS);
        String patchTag = tag("patch");
        String sendTag = tag("send");

        Throwable patchFailure;
        Throwable sendFailure;
        // Holds the patch after it loaded the draft and before it saves: its AI-provenance read waits on this lock.
        Connection holder = dataSource.getConnection();
        try {
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement()) {
                st.execute("SET LOCAL lock_timeout = '5s'");
                st.execute("LOCK TABLE campaign_ai_suggestions IN ACCESS EXCLUSIVE MODE");
            }
            Future<?> patch = executor().submit(() -> tagged(patchTag, () ->
                    service.patch(owner, c.getId(), new PatchCampaignRequest(null, null, null, "Patched subject", null, null, null))));
            awaitBlocked(patchTag, false);
            Future<?> send = executor().submit(() -> tagged(sendTag, () ->
                    service.send(c.getId(), owner, UUID.randomUUID().toString(), sendAt)));
            // The send either commits at once, or waits for the patch's row lock.
            awaitBlocked(sendTag, true, send);
            holder.rollback();
            patchFailure = catchThrowable(() -> patch.get(WAIT.toSeconds(), TimeUnit.SECONDS));
            sendFailure = catchThrowable(() -> send.get(WAIT.toSeconds(), TimeUnit.SECONDS));
        } finally {
            try {
                holder.rollback();
            } catch (SQLException ignored) {
                // Already rolled back on the happy path; the close below still returns the connection.
            }
            holder.close();
        }

        assertThat(sendFailure).as("the send went through").isNull();
        Map<String, Object> row = jdbc.queryForMap("SELECT status, subject FROM campaigns WHERE id = ?", c.getId());
        assertThat(row.get("status")).as("never 'draft' written back over the send").isEqualTo("scheduled");
        if (patchFailure == null) {
            assertThat(row.get("subject")).as("an edit that returned 200 is kept").isEqualTo("Patched subject");
        } else {
            assertThat(patchFailure.getCause()).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        }
    }

    @Test
    void aRetryRacingAnAttemptThatUsedTheLastOne_answers409() throws Exception {
        Organization org = fx.org();
        orgIds.add(org.getId());
        AuthPrincipal owner = fx.principal(fx.owner(org));
        Campaign c = draft(org.getId());
        jdbc.update("UPDATE campaigns SET status = 'failed', attempts = 2 WHERE id = ?", c.getId());
        String retryTag = tag("retry");

        Throwable refused;
        // Stands in for a claim and failed drive that land between the retry's load (attempts 2) and its update.
        Connection holder = dataSource.getConnection();
        try {
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement()) {
                st.execute("SELECT id FROM campaigns WHERE id = '" + c.getId() + "' FOR UPDATE");
            }
            Future<?> retry = executor().submit(() -> tagged(retryTag, () -> service.retry(owner, c.getId())));
            awaitBlocked(retryTag, false);
            try (Statement st = holder.createStatement()) {
                st.execute("UPDATE campaigns SET attempts = 3 WHERE id = '" + c.getId() + "'");
            }
            holder.commit();
            refused = catchThrowable(() -> retry.get(WAIT.toSeconds(), TimeUnit.SECONDS));
        } finally {
            try {
                holder.rollback();
            } catch (SQLException ignored) {
                // Committed on the happy path; the close below still returns the connection.
            }
            holder.close();
        }

        assertThat(refused).as("the retry budget is spent").isNotNull();
        assertThat(refused.getCause()).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        Map<String, Object> row = jdbc.queryForMap("SELECT status, attempts FROM campaigns WHERE id = ?", c.getId());
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(3);
    }

    private Campaign draft(UUID orgId) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Race");
        c.setStatus("draft");
        c.setSubject("Original subject");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c);
    }

    private static String tag(String what) {
        return what + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Runs {@code work} in a transaction whose backend carries {@code tag}, so the waits below see only it. */
    private void tagged(String tag, Runnable work) {
        tx.executeWithoutResult(st -> {
            jdbc.execute("SET LOCAL application_name = '" + tag + "'");
            work.run();
        });
    }

    private void awaitBlocked(String tag, boolean orDone) throws InterruptedException {
        awaitBlocked(tag, orDone, null);
    }

    /** Waits until the tagged backend is blocked on a lock, or (when {@code orDone}) its task has finished. */
    private void awaitBlocked(String tag, boolean orDone, Future<?> task) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (orDone && task != null && task.isDone()) return;
            Integer n = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND application_name = ? AND wait_event_type = 'Lock'", Integer.class, tag);
            if (n != null && n > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError(tag + " never blocked" + (orDone ? " nor finished" : "") + " within " + WAIT);
    }

    private ExecutorService executor() {
        ExecutorService e = Executors.newSingleThreadExecutor();
        executors.add(e);
        return e;
    }
}

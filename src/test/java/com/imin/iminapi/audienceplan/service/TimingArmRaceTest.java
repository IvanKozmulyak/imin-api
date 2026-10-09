package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.model.AudienceExperiment;
import com.imin.iminapi.audienceplan.repository.AudienceExperimentRepository;
import com.imin.iminapi.marketing.dto.CampaignRequests.PatchCampaignRequest;
import com.imin.iminapi.marketing.dto.CampaignSendResponse;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PgFaults;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** An edit of a slump draft racing its approval or its Momentum trigger, on real Postgres row locks: the edit wins. */
@IminIntegrationTest
class TimingArmRaceTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    enum Approval { EDIT_FIRST, APPROVAL_FIRST }

    @Autowired CampaignService service;
    @Autowired TimingArmScheduler scheduler;
    @Autowired CampaignRepository campaigns;
    @Autowired AudienceExperimentRepository experiments;
    @Autowired AudiencePlanProperties props;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private final List<UUID> orgIds = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();
    private Organization org;
    private AuthPrincipal owner;
    private Event event;

    @BeforeEach
    void setUp() {
        org = fx.org();
        orgIds.add(org.getId());
        jdbc.update("UPDATE organizations SET timezone = 'Europe/Paris', legal_name = 'Race SAS', "
                + "legal_contact = 'legal@example.test' WHERE id = ?", org.getId());
        User user = fx.owner(org);
        owner = fx.principal(user);
        event = fx.event(org, user, EventStatus.LIVE, Instant.now().plus(20, ChronoUnit.DAYS));
        flips.set(props, "sendsEnabled", true);
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        try {
            for (ExecutorService e : executors) e.shutdownNow();
            for (ExecutorService e : executors) {
                if (!e.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS)) throw new AssertionError("a thread did not stop");
            }
        } finally {
            try {
                CampaignRows.delete(jdbc, orgIds);
            } finally {
                OrgRows.delete(jdbc, orgIds);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Approval.class)
    void anApprovalRacingAnEdit_neverLeavesTheEditedDraftArmed(Approval order) throws Exception {
        UUID campaign = slumpDraft();
        UUID arm = arm(campaign);

        Future<?> edit;
        Future<CampaignSendResponse> approval;
        if (order == Approval.EDIT_FIRST) {
            // The edit holds the campaign row lock and has not committed when the approval reads the draft.
            try (PgFaults.Pause paused = PgFaults.pauseWrites(dataSource, "campaigns", "id", campaign)) {
                edit = submit(() -> service.patch(owner, campaign, editSubject()));
                paused.awaitBlocked(WAIT);
                approval = submit(() -> approve(campaign));
                awaitRowLockWaitOrDone(approval);
                paused.release();
                await(edit);
                await(approval);
            }
        } else {
            // The approval has decided to arm and not committed when the edit arrives.
            try (PgFaults.Pause paused = PgFaults.pauseWrites(dataSource, "audience_experiments", "id", arm)) {
                approval = submit(() -> approve(campaign));
                paused.awaitBlocked(WAIT);
                edit = submit(() -> service.patch(owner, campaign, editSubject()));
                awaitRowLockWaitOrDone(edit);
                paused.release();
                await(approval);
                await(edit);
            }
        }

        assertThat(failure(edit)).as("the edit went through").isNull();
        assertThat(subject(campaign)).isEqualTo("Edited");
        assertThat(armedAt(arm)).as("an edited draft waits for a fresh approval").isNull();
        if (order == Approval.EDIT_FIRST) {
            assertThat(failure(approval)).as("an approval of the pre-edit draft is refused")
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.getMessage()).contains("edited meanwhile");
                    });
        } else {
            assertThat(failure(approval)).isNull();
            assertThat(approval.get().armed()).isTrue();
        }
        assertThat(status(campaign)).isEqualTo("draft");
    }

    @Test
    void aSlumpRacingAnEdit_neverSchedulesTheEditedDraft() throws Exception {
        UUID campaign = slumpDraft();
        UUID arm = arm(campaign);
        jdbc.update("UPDATE audience_experiments SET armed_at = now() WHERE id = ?", arm);

        Future<?> edit;
        Future<Integer> slump;
        // The edit has disarmed and holds the row lock, uncommitted, when the slump reads the armed list.
        try (PgFaults.Pause paused = PgFaults.pauseWrites(dataSource, "campaigns", "id", campaign)) {
            edit = submit(() -> service.patch(owner, campaign, editSubject()));
            paused.awaitBlocked(WAIT);
            slump = submit(() -> scheduler.fireSlump(org.getId(), event.getId()));
            awaitRowLockWaitOrDone(slump);
            paused.release();
            await(edit);
            await(slump);
        }

        assertThat(failure(slump)).isNull();
        assertThat(slump.get()).as("a draft disarmed by an edit is not scheduled").isZero();
        assertThat(failure(edit)).isNull();
        assertThat(status(campaign)).isEqualTo("draft");
        assertThat(subject(campaign)).isEqualTo("Edited");
        assertThat(armedAt(arm)).isNull();
    }

    private CampaignSendResponse approve(UUID campaign) {
        return service.send(campaign, owner, "idem-" + UUID.randomUUID(), null);
    }

    private static PatchCampaignRequest editSubject() {
        return new PatchCampaignRequest(null, null, null, "Edited", null, null, null);
    }

    private UUID slumpDraft() {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(org.getId());
        c.setChannel("email");
        c.setName("Slump race");
        c.setStatus("draft");
        c.setOrigin(AudiencePlanAccess.CAMPAIGN_ORIGIN);
        c.setEventId(event.getId());
        c.setSubject("Original");
        c.setBodyMd("B");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaigns.save(c).getId();
    }

    private UUID arm(UUID campaign) {
        AudienceExperiment e = new AudienceExperiment();
        e.setOrgId(org.getId());
        e.setEventId(event.getId());
        e.setArm("slump");
        e.setCampaignId(campaign);
        e.setMembers(1);
        e.setSeed(1L);
        return experiments.save(e).getId();
    }

    private Instant armedAt(UUID arm) {
        OffsetDateTime at = jdbc.queryForObject("SELECT armed_at FROM audience_experiments WHERE id = ?",
                OffsetDateTime.class, arm);
        return at == null ? null : at.toInstant();
    }

    private String status(UUID campaign) {
        return jdbc.queryForObject("SELECT status FROM campaigns WHERE id = ?", String.class, campaign);
    }

    private String subject(UUID campaign) {
        return jdbc.queryForObject("SELECT subject FROM campaigns WHERE id = ?", String.class, campaign);
    }

    /** Waits until a statement on {@code campaigns} queues behind another transaction's row lock, or the task ends. */
    private void awaitRowLockWaitOrDone(Future<?> task) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (task.isDone()) return;
            // A paused writer waits on an advisory lock; only a row-lock wait counts here.
            Integer n = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock' AND wait_event IN ('transactionid', 'tuple') "
                    + "AND query ~* 'campaigns'", Integer.class);
            if (n != null && n > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("the second writer neither blocked on the campaign row nor finished within " + WAIT);
    }

    private static void await(Future<?> task) throws InterruptedException {
        try {
            task.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException ignored) {
            // Read through failure() by the assertions.
        } catch (TimeoutException e) {
            throw new AssertionError("a racing writer did not finish within " + WAIT, e);
        }
    }

    private static Throwable failure(Future<?> done) throws InterruptedException {
        try {
            done.get();
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private <T> Future<T> submit(Callable<T> work) {
        ExecutorService e = Executors.newSingleThreadExecutor();
        executors.add(e);
        return e.submit(work);
    }
}

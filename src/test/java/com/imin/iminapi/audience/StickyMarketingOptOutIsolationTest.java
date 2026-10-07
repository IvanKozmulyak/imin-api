package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.MarketingOptOut;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PgFaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The sticky marketing_optouts write can never fail the unsubscribe (REQUIRES_NEW recorder, catch outside it);
 * asserts committed state, so no @Transactional. ConsentServiceStickyRaceTest pins the non-duplicate violation.
 */
@IminIntegrationTest
class StickyMarketingOptOutIsolationTest {

    @Autowired ConsentService consentService;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired ConsentRecordRepository consentRecords;
    @Autowired MarketingOptOutRepository optOuts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    private final List<UUID> orgIds = new ArrayList<>();
    private final List<String> emails = new ArrayList<>();

    /** Own rows only: sticky opt-outs and consumers are keyed by address across orgs. */
    @AfterEach
    void tearDown() {
        for (UUID orgId : orgIds) {
            jdbc.update("delete from marketing_optouts where org_id = ?", orgId);
            jdbc.update("delete from memberships where org_id = ?", orgId);
        }
        for (String email : emails) jdbc.update("delete from consumers where normalized_email = ?", email);
    }

    // Two unsubscribes racing on Postgres: the loser's insert waits on the winner's uncommitted row,
    // then gets a genuine duplicate-key violation once the winner commits. That must be a no-op.
    @Test
    void aRealDuplicateKeyViolation_doesNotRollBackTheUnsubscribe() throws Exception {
        UUID orgId = UUID.randomUUID();
        String email = seedEmail("race");
        UUID membershipId = seedMembership(orgId, email);
        TransactionTemplate winner = new TransactionTemplate(txManager);
        winner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ExecutorService loserThread = Executors.newSingleThreadExecutor();
        try {
            Future<?> loser = winner.execute(status -> {
                optOuts.saveAndFlush(MarketingOptOut.of(email, orgId, "email", "one_click"));
                Future<?> f = loserThread.submit(() -> consentService.unsubscribe(orgId, membershipId,
                        "footer_link", ConsentOrigin.DATA_SUBJECT, null));
                awaitInsertBlockedOnALock(f);
                return f;
            });

            assertThatCode(() -> loser.get(30, TimeUnit.SECONDS))
                    .as("a duplicate sticky row must be success, not a 500 on the RFC 8058 path")
                    .doesNotThrowAnyException();
        } finally {
            loserThread.shutdownNow();
        }

        assertUnsubscribeCommitted(orgId, membershipId);
        // The winner's row stands untouched: still one row, still the earliest one.
        assertThat(optOuts.findByEmailNormalized(email))
                .singleElement()
                .satisfies(row -> assertThat(row.getSource()).isEqualTo("one_click"));
    }

    /** Waits until a backend is blocked on a lock inside its marketing_optouts insert. */
    private void awaitInsertBlockedOnALock(Future<?> loser) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!insertIsBlocked()) {
            if (loser.isDone()) throw new AssertionError("the loser finished without waiting on the winner's row");
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("the loser's insert never blocked on the winner's uncommitted row");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the lock", e);
            }
        }
    }

    // pg_stat_activity is snapshotted once per transaction, and this poll runs inside the winner's.
    private boolean insertIsBlocked() {
        jdbc.queryForList("select pg_stat_clear_snapshot()");
        return jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock'"
                + " and query ilike 'insert into marketing_optouts%'", Integer.class) > 0;
    }

    /** Any non-duplicate failure — here Postgres rejecting the insert — still leaves the unsubscribe standing. */
    @Test
    void anyOtherFailureInTheStickyWrite_doesNotRollBackTheUnsubscribe() {
        UUID orgId = UUID.randomUUID();
        String email = seedEmail("boom");
        UUID membershipId = seedMembership(orgId, email);

        try (var fault = PgFaults.failWrites(jdbc, "marketing_optouts", "org_id", orgId)) {
            assertThatCode(() -> consentService.unsubscribe(orgId, membershipId, "one_click",
                    ConsentOrigin.DATA_SUBJECT, null))
                    .doesNotThrowAnyException();
        }

        assertUnsubscribeCommitted(orgId, membershipId);
        assertThat(optOuts.findByEmailNormalized(email)).as("the faulted sticky row was not written").isEmpty();
    }

    /** The unsubscribe and its consent proof, read back committed; a poisoned transaction would have thrown. */
    private void assertUnsubscribeCommitted(UUID orgId, UUID membershipId) {
        Membership after = memberships.findByIdAndOrgId(membershipId, orgId).orElseThrow();
        assertThat(after.getConsentStatus()).isEqualTo("unsubscribed");
        assertThat(after.getConsentBasis()).isNull();
        assertThat(consentRecords.findByMembershipId(membershipId))
                .as("the immutable consent proof row is the legal record — it must commit")
                .anySatisfy(r -> {
                    assertThat(r.getChannel()).isEqualTo("email");
                    assertThat(r.getStatus()).isEqualTo("unsubscribed");
                });
    }

    private String seedEmail(String prefix) {
        String email = prefix + "-" + UUID.randomUUID() + "@example.com";
        emails.add(email);
        return email;
    }

    private UUID seedMembership(UUID orgId, String normalizedEmail) {
        orgIds.add(orgId);
        Consumer c = new Consumer();
        c.setNormalizedEmail(normalizedEmail);
        c = consumers.save(c);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        return memberships.save(m).getMembershipId();
    }
}

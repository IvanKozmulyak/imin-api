package com.imin.iminapi.audience;

import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.service.ticket.TicketsIssuedEvent;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PgFaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Membership writers that race a consent change keep both: each one takes the row lock before trusting the row. */
@IminIntegrationTest
class MembershipConsentLockTest {

    private static final String PHONE = "+380675554433";

    @Autowired AudienceOrderProjector projector;
    @Autowired ConsentService consentService;
    @Autowired MembershipRepository memberships;
    @Autowired OrderRepository orders;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager txManager;
    @Autowired @Qualifier("taskExecutor") Executor asyncExecutor;
    @Autowired @Qualifier(FanFeatureExecutors.LIVE) Executor fanFeatureExecutor;

    @AfterEach
    void drain() {
        AsyncDrain.drain(asyncExecutor);
        AsyncDrain.drain(fanFeatureExecutor);
    }

    @Test
    void backfillRowRacingAnUnsubscribe_leavesTheMemberUnsubscribed() throws Exception {
        Organization org = fx.org();
        String email = fx.email("backfill");
        orderIn(org, email);
        UUID mid = subscribedMember(org, email);

        try (PgFaults.TableHold hold = PgFaults.pauseReads(dataSource, "orders")) {
            // The backfill row has read the member and is held in its recompute.
            CompletableFuture<Void> backfill = CompletableFuture.runAsync(
                    () -> projector.upsertMembership(org.getId(), email, email));
            hold.awaitBlocked(Duration.ofSeconds(10));
            CompletableFuture<Void> unsubscribe = CompletableFuture.runAsync(() -> consentService.unsubscribe(
                    org.getId(), mid, "footer_link", "email", ConsentOrigin.DATA_SUBJECT, null));
            // It either waits for the backfill's row lock, or (without one) commits before the backfill writes.
            awaitUntil(() -> unsubscribe.isDone() || lockWaitOn("memberships"), Duration.ofSeconds(10));
            hold.release();
            backfill.get(10, TimeUnit.SECONDS);
            unsubscribe.get(10, TimeUnit.SECONDS);
        }

        assertThat(row(mid)).containsEntry("consent_status", "unsubscribed").containsEntry("consent_basis", null);
    }

    @Test
    void captureRacingAProjection_keepsTheCheckoutSmsOptInAndPhone() throws Exception {
        Organization org = fx.org();
        String email = fx.email("capture");
        UUID mid = member(org, email);
        Order order = orderIn(org, email);
        order.setBuyerPhone(PHONE);
        order.setSmsMarketingOptIn(true);
        orders.save(order);

        try (PgFaults.Pause pause = PgFaults.pauseWrites(dataSource, "consent_records", "membership_id", mid)) {
            // The capture has read the member and is held before its commit, which writes the whole row.
            CompletableFuture<Void> capture = CompletableFuture.runAsync(() -> consentService.capture(
                    org.getId(), mid, "explicit", "manual", "Organizer recorded consent", "email", null));
            pause.awaitBlocked(Duration.ofSeconds(10));
            projector.onTicketsIssued(new TicketsIssuedEvent(order.getId()));
            // The projection either waits for the capture's row lock, or (without one) commits its phone first.
            awaitUntil(() -> lockWaitOn("memberships") || row(mid).get("phone_e164") != null, Duration.ofSeconds(10));
            pause.release();
            capture.get(10, TimeUnit.SECONDS);
            AsyncDrain.drain(asyncExecutor);
        }

        assertThat(row(mid)).containsEntry("phone_e164", PHONE).containsEntry("sms_consent_status", "subscribed")
                .containsEntry("consent_status", "subscribed").containsEntry("consent_basis", "explicit");
    }

    @Test
    void captureOverACopyReadEarlierInItsTransaction_keepsWhatAnotherWriterCommittedSince() {
        Organization org = fx.org();
        String email = fx.email("stale");
        UUID mid = member(org, email);

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            // The caller's own earlier, unlocked read of the member.
            memberships.findByIdAndOrgId(mid, org.getId()).orElseThrow();
            commitElsewhere("update memberships set phone_e164 = ?, sms_consent_status = 'subscribed',"
                    + " sms_consent_basis = 'explicit' where membership_id = ?", PHONE, mid);
            consentService.capture(org.getId(), mid, "explicit", "manual", "Organizer recorded consent", "email", null);
        });

        assertThat(row(mid)).containsEntry("phone_e164", PHONE).containsEntry("sms_consent_status", "subscribed")
                .containsEntry("consent_status", "subscribed");
    }

    private Order orderIn(Organization org, String email) {
        return fx.order(fx.event(org, fx.owner(org), EventStatus.LIVE, Instant.now().plusSeconds(86_400)), email);
    }

    private UUID member(Organization org, String email) {
        projector.upsertMembership(org.getId(), email, email);
        return jdbc.queryForObject("select m.membership_id from memberships m join consumers c"
                + " on c.consumer_id = m.consumer_id where m.org_id = ? and c.normalized_email = ?",
                UUID.class, org.getId(), email);
    }

    private UUID subscribedMember(Organization org, String email) {
        UUID mid = member(org, email);
        consentService.capture(org.getId(), mid, "explicit", "manual", "Organizer recorded consent", "email", null);
        assertThat(row(mid)).containsEntry("consent_status", "subscribed");
        return mid;
    }

    private Map<String, Object> row(UUID mid) {
        return jdbc.queryForMap("select consent_status, consent_basis, phone_e164, sms_consent_status"
                + " from memberships where membership_id = ?", mid);
    }

    /** A write on a connection of its own, committed at once: another request, not this transaction. */
    private void commitElsewhere(String sql, String phone, UUID mid) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            c.setAutoCommit(true);
            ps.setString(1, phone);
            ps.setObject(2, mid);
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean lockWaitOn(String table) {
        Integer n = jdbc.queryForObject("select count(*) from pg_stat_activity where datname = current_database()"
                + " and wait_event_type = 'Lock' and query ilike ?", Integer.class, "%" + table + "%");
        return n != null && n > 0;
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting", e);
            }
        }
        throw new AssertionError("condition not reached within " + timeout);
    }
}

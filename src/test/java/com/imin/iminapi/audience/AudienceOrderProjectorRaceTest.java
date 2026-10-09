package com.imin.iminapi.audience;

import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.AudienceRedeemProjector;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.MembershipProjector;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.dto.publicapi.SmsConsentRequest;
import com.imin.iminapi.dto.publicapi.SmsConsentResponse;
import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.service.audience.SmsConsentService;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.service.ticket.TicketRedeemedEvent;
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
import static org.assertj.core.api.Assertions.assertThatCode;

/** Two orders for one new email, projected at once through the real async listener, on Postgres. */
@IminIntegrationTest
class AudienceOrderProjectorRaceTest {

    @Autowired AudienceOrderProjector projector;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired @Qualifier("taskExecutor") Executor asyncExecutor;
    @Autowired @Qualifier(FanFeatureExecutors.LIVE) Executor fanFeatureExecutor;
    @Autowired OrderRepository orders;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired MembershipProjector membershipProjector;
    @Autowired ConsentService consentService;
    @Autowired OrganizationRepository orgs;
    @Autowired AudiencePlanLogic planLogic;
    @Autowired PlatformTransactionManager txManager;
    @Autowired SmsConsentService smsConsent;
    @Autowired ErasedAddressRepository erasedAddresses;

    private static final String PROOF = "Email me about this organizer's events. Unsubscribe anytime.";

    @AfterEach
    void drain() {
        AsyncDrain.drain(asyncExecutor);
        AsyncDrain.drain(fanFeatureExecutor);
    }

    private Order orderIn(Organization org, String email) {
        return fx.order(fx.event(org, fx.owner(org), EventStatus.LIVE, Instant.now().plusSeconds(86_400)), email);
    }

    @Test
    void concurrentOrdersForOneNewEmail_bothMembershipsExistOnOneConsumer() {
        String email = fx.email("race");
        Organization orgA = fx.org();
        Organization orgB = fx.org();
        Order a = orderIn(orgA, email);
        Order b = orderIn(orgB, email);

        try (PgFaults.Pause pause = PgFaults.pauseWrites(dataSource, "memberships", "org_id", orgA.getId())) {
            // A has inserted the consumer and is held before its commit.
            projector.onTicketsIssued(new TicketsIssuedEvent(a.getId()));
            pause.awaitBlocked(Duration.ofSeconds(10));
            // B misses the uncommitted consumer and its INSERT waits on A's row.
            projector.onTicketsIssued(new TicketsIssuedEvent(b.getId()));
            awaitInsertBlocked("consumers", Duration.ofSeconds(10));
            pause.release();
            AsyncDrain.drain(asyncExecutor);
        }

        List<UUID> consumerIds = jdbc.queryForList(
                "select consumer_id from consumers where normalized_email = ?", UUID.class, email);
        assertThat(consumerIds).hasSize(1);
        UUID consumerId = consumerIds.get(0);
        assertThat(jdbc.queryForList("select org_id from memberships where consumer_id = ?", UUID.class, consumerId))
                .containsExactlyInAnyOrder(orgA.getId(), orgB.getId());
        Map<String, Object> mb = jdbc.queryForMap(
                "select display_name, first_touch_src from memberships where org_id = ? and consumer_id = ?",
                orgB.getId(), consumerId);
        assertThat(mb).containsEntry("display_name", email).containsEntry("first_touch_src", "organic");
    }

    @Test
    void concurrentOrdersFromOneBuyerToOneOrg_oneMembershipCountsBothAndKeepsBothConsents() {
        String email = fx.email("same-org");
        Organization org = fx.org();
        // The consumer already exists, so both projections race on the membership alone.
        projector.upsertMembership(fx.org().getId(), email, email);
        Order a = optedIn(orderIn(org, email));

        try (PgFaults.Pause pause = PgFaults.pauseWrites(dataSource, "consent_records", "order_id", a.getId())) {
            // A has inserted the membership, counted only its own order, and is held before its commit.
            projector.onTicketsIssued(new TicketsIssuedEvent(a.getId()));
            pause.awaitBlocked(Duration.ofSeconds(10));
            Order b = optedIn(orderIn(org, email));
            // B misses the uncommitted membership and its INSERT waits on A's row.
            projector.onTicketsIssued(new TicketsIssuedEvent(b.getId()));
            awaitInsertBlocked("memberships", Duration.ofSeconds(10));
            pause.release();
            AsyncDrain.drain(asyncExecutor);

            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select membership_id, orders, spend_minor, consent_status, consent_basis from memberships"
                            + " where org_id = ?", org.getId());
            assertThat(rows).hasSize(1);
            Map<String, Object> m = rows.get(0);
            assertThat(m).containsEntry("orders", 2)
                    .containsEntry("spend_minor", a.getTotalMinor() + b.getTotalMinor())
                    .containsEntry("consent_status", "subscribed").containsEntry("consent_basis", "explicit");
            assertThat(jdbc.queryForList("select order_id from consent_records where membership_id = ?"
                            + " and channel = 'email' and lawful_basis = 'explicit'", UUID.class, m.get("membership_id")))
                    .containsExactlyInAnyOrder(a.getId(), b.getId());
        }
    }

    @Test
    void concurrentFirstSmsConsentsForOneNewEmail_bothRecordedOnOneConsumer() throws Exception {
        String email = fx.email("sms-race");
        Organization orgA = fx.org();
        Organization orgB = fx.org();
        Order a = orderIn(orgA, email);
        Order b = orderIn(orgB, email);
        SmsConsentRequest req = new SmsConsentRequest("+380671234567", true, "Text me about events.");

        CompletableFuture<SmsConsentResponse> first;
        CompletableFuture<SmsConsentResponse> second;
        try (PgFaults.Pause pause = PgFaults.pauseWrites(dataSource, "memberships", "org_id", orgA.getId())) {
            // A has inserted the consumer and is held before its commit.
            first = CompletableFuture.supplyAsync(() -> smsConsent.submit(a.getToken(), req));
            pause.awaitBlocked(Duration.ofSeconds(10));
            // B misses the uncommitted consumer and its INSERT waits on A's row.
            second = CompletableFuture.supplyAsync(() -> smsConsent.submit(b.getToken(), req));
            awaitInsertBlocked("consumers", Duration.ofSeconds(10));
            pause.release();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        List<UUID> consumerIds = jdbc.queryForList(
                "select consumer_id from consumers where normalized_email = ?", UUID.class, email);
        assertThat(consumerIds).hasSize(1);
        assertThat(jdbc.queryForList("select org_id from memberships where consumer_id = ?"
                        + " and phone_e164 = '+380671234567' and sms_consent_status = 'subscribed'",
                UUID.class, consumerIds.get(0)))
                .containsExactlyInAnyOrder(orgA.getId(), orgB.getId());
    }

    @Test
    void concurrentProjectionsOfAnExistingMember_neitherWritesOverTheOther() {
        String email = fx.email("existing");
        Organization org = fx.org();
        projector.upsertMembership(org.getId(), email, email);
        Order a = optedIn(orderIn(org, email));
        Order b = orderIn(org, email);
        b.setBuyerPhone("+380671112233");
        b.setSmsMarketingOptIn(true);
        orders.save(b);

        try (PgFaults.Pause pause = PgFaults.pauseWrites(dataSource, "consent_records", "order_id", a.getId())) {
            // A has read the member and is held before its commit, which writes the whole row.
            projector.onTicketsIssued(new TicketsIssuedEvent(a.getId()));
            pause.awaitBlocked(Duration.ofSeconds(10));
            projector.onTicketsIssued(new TicketsIssuedEvent(b.getId()));
            // B either waits for A's row lock, or (without one) commits its phone first and A then writes over it.
            awaitUntil(() -> lockWaitOn("memberships") || smsPhoneOf(org) != null, Duration.ofSeconds(10));
            pause.release();
            AsyncDrain.drain(asyncExecutor);
        }

        Map<String, Object> m = jdbc.queryForMap("select orders, consent_status, phone_e164, sms_consent_status"
                + " from memberships where org_id = ?", org.getId());
        assertThat(m).containsEntry("orders", 2).containsEntry("consent_status", "subscribed")
                .containsEntry("phone_e164", "+380671112233").containsEntry("sms_consent_status", "subscribed");
    }

    private String smsPhoneOf(Organization org) {
        return jdbc.queryForObject("select phone_e164 from memberships where org_id = ?", String.class, org.getId());
    }

    private boolean lockWaitOn(String table) {
        Integer n = jdbc.queryForObject("select count(*) from pg_stat_activity where datname = current_database()"
                + " and wait_event_type = 'Lock' and query ilike ?", Integer.class, "%" + table + "%");
        return n != null && n > 0;
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition, Duration timeout) {
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

    private Order optedIn(Order order) {
        order.setMarketingOptIn(true);
        order.setMarketingOptInProof(PROOF);
        return orders.save(order);
    }

    @Test
    void membershipCommitFailure_isCaughtAndRollsBackTheProjection() {
        String email = fx.email("fail");
        Organization org = fx.org();
        // The member exists before the order, so the projection only UPDATEs it, and that runs at the commit flush.
        projector.upsertMembership(org.getId(), email, email);
        Order order = orderIn(org, email);
        // Plain instance so the projection runs synchronously on this thread.
        AudienceOrderProjector sync = new AudienceOrderProjector(orders, consumers, memberships, membershipProjector,
                consentService, e -> { }, orgs, planLogic, txManager, erasedAddresses);

        try (PgFaults.Fault fault = PgFaults.failWrites(jdbc, "memberships", "org_id", org.getId())) {
            assertThatCode(() -> sync.onTicketsIssued(new TicketsIssuedEvent(order.getId())))
                    .doesNotThrowAnyException();
        }

        assertThat(jdbc.queryForObject("select orders from memberships where org_id = ?", Integer.class, org.getId()))
                .isZero();
    }

    @Test
    void redeemProjectionCommitFailure_isCaughtAndRollsBackTheProjection() {
        String email = fx.email("redeem-fail");
        Organization org = fx.org();
        Order order = orderIn(org, email);
        fx.ticket(order, Ticket.STATE_REDEEMED);
        // The member exists before the scan, so the redeem projection only UPDATEs it, at the commit flush.
        projector.upsertMembership(org.getId(), email, email);
        jdbc.update("update memberships set attended = 0 where org_id = ?", org.getId());
        // Plain instance so the projection runs synchronously on this thread.
        AudienceRedeemProjector sync = new AudienceRedeemProjector(orders, projector, erasedAddresses, txManager);

        try (PgFaults.Fault fault = PgFaults.failWrites(jdbc, "memberships", "org_id", org.getId())) {
            assertThatCode(() -> sync.onTicketRedeemed(new TicketRedeemedEvent(order.getId(), order.getEventId())))
                    .doesNotThrowAnyException();
        }

        assertThat(jdbc.queryForObject("select attended from memberships where org_id = ?", Integer.class, org.getId()))
                .isZero();
    }

    /** Waits until a session's INSERT into {@code table} is waiting on a row lock held by another transaction. */
    private void awaitInsertBlocked(String table, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Integer n = jdbc.queryForObject("select count(*) from pg_stat_activity where datname = current_database()"
                    + " and wait_event_type = 'Lock' and query ilike ?", Integer.class, "insert into " + table + " %");
            if (n != null && n > 0) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the consumer insert", e);
            }
        }
        throw new AssertionError("no INSERT into " + table + " blocked within " + timeout);
    }
}

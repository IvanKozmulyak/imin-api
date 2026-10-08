package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.AudienceBackfillCompleted;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.audience.service.ConsentChanged;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.MembershipProjected;
import com.imin.iminapi.audience.service.MembershipProjector;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.service.ticket.TicketRedeemedEvent;
import com.imin.iminapi.service.ticket.TicketsIssuedEvent;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The audience writes that must re-trigger the fan-feature projection publish their event. */
@IminIntegrationTest
@RecordApplicationEvents
class FanFeatureTriggerEventsTest {

    @Autowired ConsentService consentService;
    @Autowired ApplicationEvents published;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired AudiencePlanLogic planLogic;
    @Autowired UserRepository users;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired MembershipProjector membershipProjector;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier(FanFeatureExecutors.LIVE) Executor liveExecutor;
    @Autowired @Qualifier(FanFeatureExecutors.RECOMPUTE) Executor recomputeExecutor;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired AudiencePlanProperties planProps;
    @Autowired PropertyFlips flips;

    private FanFeatureFixtures fx;
    private final List<UUID> orgIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fx = new FanFeatureFixtures(orgs, users, events, orders, tickets, consumers, memberships);
    }

    @AfterEach
    void tearDown() {
        AsyncDrain.drain(liveExecutor);
        if (orgIds.isEmpty()) return;
        String in = String.join(",", Collections.nCopies(orgIds.size(), "?"));
        List<UUID> consumerIds = jdbc.queryForList("select consumer_id from memberships where org_id in (" + in + ")",
                UUID.class, orgIds.toArray());
        jdbc.update("delete from memberships where org_id in (" + in + ")", orgIds.toArray());
        for (UUID c : consumerIds) jdbc.update("delete from consumers where consumer_id = ?", c);
        OrgRows.delete(jdbc, orgIds);
        orgIds.clear();
    }

    private FanFeatureFixtures.Org org() {
        FanFeatureFixtures.Org org = fx.org("UTC");
        orgIds.add(org.id());
        return org;
    }

    /** One ConsentService write, for (org, membership). */
    interface ConsentWrite {
        void run(ConsentService s, UUID orgId, UUID membershipId);
    }

    static Stream<Arguments> consentWrites() {
        return Stream.of(
                Arguments.of("capture", (ConsentWrite) (s, org, mid) -> s.capture(org, mid, "explicit", "checkout",
                        "Ticked the box", "email", null, null, ConsentOrigin.DATA_SUBJECT, null), false),
                Arguments.of("unsubscribe", (ConsentWrite) (s, org, mid) -> s.unsubscribe(org, mid, "one_click",
                        "email", ConsentOrigin.OPERATOR, null), false),
                Arguments.of("import capture", (ConsentWrite) (s, org, mid) -> s.capture(org, mid, "explicit",
                        ImportProvenanceWriter.SOURCE, "Attested", "email", null), true),
                Arguments.of("global unsubscribe", (ConsentWrite) (s, org, mid) -> s.unsubscribe(org, mid,
                        "preference_centre_global", "email", ConsentOrigin.DATA_SUBJECT_GLOBAL, null), true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("consentWrites")
    void consentWrite_publishesConsentChanged_deferrableOnlyForBulkOrGlobalWrites(String name, ConsentWrite write,
                                                                                boolean deferrable) {
        UUID orgId = UUID.randomUUID();
        orgIds.add(orgId);
        Membership m = fx.membership(orgId, FanFeatureFixtures.email("consent"));

        write.run(consentService, orgId, m.getMembershipId());

        assertThat(published.stream(ConsentChanged.class))
                .containsExactly(new ConsentChanged(orgId, m.getMembershipId(), deferrable));
    }

    enum Trigger { CONSENT, TICKET_REDEEMED, MEMBERSHIP_PROJECTED }

    static Stream<Arguments> liveTriggers() {
        return Stream.of(Trigger.values()).flatMap(t -> Stream.of(Arguments.of(t, true), Arguments.of(t, false)));
    }

    @ParameterizedTest(name = "{0} committed={1}")
    @MethodSource("liveTriggers")
    void liveTrigger_recomputesFeaturesAfterCommitOnly(Trigger trigger, boolean commit) {
        FanFeatureFixtures.Org org = org();
        String email = FanFeatureFixtures.email("after-commit");
        Event ev = fx.event(org, "Pop", Instant.now().minus(3, ChronoUnit.DAYS));
        Order order = fx.paidOrder(ev, email, Instant.now().minus(3, ChronoUnit.DAYS), Ticket.STATE_ISSUED);
        // Committed before the trigger, so a listener that fired on rollback would find a row to write.
        Membership m = fx.membership(org.id(), email);

        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            switch (trigger) {
                case CONSENT -> consentService.capture(org.id(), m.getMembershipId(), "explicit", "checkout",
                        "Ticked the box", "email", null, null, ConsentOrigin.DATA_SUBJECT, null);
                case TICKET_REDEEMED -> publisher.publishEvent(new TicketRedeemedEvent(order.getId(), ev.getId()));
                case MEMBERSHIP_PROJECTED -> publisher.publishEvent(new MembershipProjected(org.id(), email));
            }
            if (!commit) tx.setRollbackOnly();
        });

        assertThat(awaitFeatureRows(m.getMembershipId(), commit)).isEqualTo(commit ? 1 : 0);
    }

    /**
     * The drain is the proof: dispatch submits on the committing thread, so any recompute is done once it returns.
     * The poll is margin for the commit rows; a rollback row re-reads for 100 ms.
     */
    private int awaitFeatureRows(UUID membershipId, boolean expectRow) {
        AsyncDrain.drain(liveExecutor);
        long deadline = System.nanoTime() + (expectRow ? 10_000_000_000L : 100_000_000L);
        int rows;
        do {
            rows = jdbc.queryForObject("select count(*) from fan_features where membership_id = ?", Integer.class,
                    membershipId);
            if (rows > 0) return rows;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for a fan_features row", e);
            }
        } while (System.nanoTime() < deadline);
        return rows;
    }

    @Test
    void backfillCompleted_recomputesOnTheRecomputePool_notTheCallersThread() {
        // Every org off the list, so the recompute pass reads pages and writes nothing to other tests' rows.
        flips.set(planProps, "betaOrgIds", Set.of(UUID.randomUUID()));
        AsyncDrain.drain(recomputeExecutor);
        long before = ((ThreadPoolTaskExecutor) recomputeExecutor).getThreadPoolExecutor().getTaskCount();

        publisher.publishEvent(new AudienceBackfillCompleted(1, 0));

        assertThat(((ThreadPoolTaskExecutor) recomputeExecutor).getThreadPoolExecutor().getTaskCount())
                .isEqualTo(before + 1);
        AsyncDrain.drain(recomputeExecutor);
    }

    // Plain projector instances so onTicketsIssued runs on this thread with a capturing publisher.

    @Test
    void ticketsIssued_publishesMembershipProjectedWithTheNormalizedEmail() {
        FanFeatureFixtures.Org org = org();
        String email = FanFeatureFixtures.email("issued");
        Order o = fx.paidOrder(fx.event(org, "Pop", Instant.now().plus(3, ChronoUnit.DAYS)),
                "  " + email.toUpperCase() + " ", Instant.now(), Ticket.STATE_ISSUED);
        List<Object> captured = new ArrayList<>();

        new AudienceOrderProjector(orders, consumers, memberships, membershipProjector, consentService, captured::add, orgs, planLogic)
                .onTicketsIssued(new TicketsIssuedEvent(o.getId()));

        assertThat(captured).containsExactly(new MembershipProjected(org.id(), email));
    }

    @Test
    void ticketsIssued_unknownOrder_publishesNothing() {
        List<Object> captured = new ArrayList<>();

        new AudienceOrderProjector(orders, consumers, memberships, membershipProjector, consentService, captured::add, orgs, planLogic)
                .onTicketsIssued(new TicketsIssuedEvent(UUID.randomUUID()));

        assertThat(captured).isEmpty();
    }
}

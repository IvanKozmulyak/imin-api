package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentChanged;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.MembershipProjected;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.model.FanFeature;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.audienceplan.repository.FanFeatureTarget;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.service.ticket.TicketRedeemedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FanFeatureProjector against H2 with the real repositories. The projector is built as a plain
 * instance with a direct executor so its listeners run on the test thread.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class FanFeatureProjectorTest {

    private static final String TECHNO = "House & Techno";

    @Autowired FanFeatureRepository features;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired EventRepository events;
    @Autowired ConsentRecordRepository consents;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired AudiencePlanLogic logic;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ConsentService consentService;
    @Autowired JdbcTemplate jdbc;

    private FanFeatureFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new FanFeatureFixtures(orgs, users, events, orders, tickets, consumers, memberships);
    }

    private FanFeatureProjector projector(AudiencePlanAccess access) {
        return projector(access, Runnable::run);
    }

    private FanFeatureProjector projector(AudiencePlanAccess access, Executor executor) {
        return new FanFeatureProjector(features, orders, tickets, events, consents, orgs, consumers, memberships,
                access, logic, clock, txManager, executor);
    }

    private FanFeatureProjector projector(Executor executor) {
        return projector(new AudiencePlanAccess(new AudiencePlanProperties()), executor);
    }

    private FanFeatureProjector projector() {
        return projector(new AudiencePlanAccess(new AudiencePlanProperties()));
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
    }

    // ---- purchase path ----

    @Test
    void purchase_writesEveryColumnFromTheCalculator() {
        FanFeatureFixtures.Org org = fx.org("Europe/Paris");
        String email = FanFeatureFixtures.email("buyer");
        Event ev = fx.event(org, TECHNO, daysAgo(10));
        Order o = fx.paidOrder(ev, email, daysAgo(10), Ticket.STATE_ISSUED, Ticket.STATE_REDEEMED);
        Membership m = fx.membership(org.id(), email);

        projector().onMembershipProjected(new MembershipProjected(org.id(), email));

        FanFeature f = features.findById(m.getMembershipId()).orElseThrow();
        assertThat(f.getOrgId()).isEqualTo(org.id());
        assertThat(f.getPaidOrders()).isEqualTo(1);
        assertThat(f.getFirstPaidPurchaseAt()).isEqualTo(o.getCreatedAt());
        assertThat(f.getLastPaidPurchaseAt()).isEqualTo(o.getCreatedAt());
        assertThat(f.getFanClass()).isEqualTo("first_timer");
        assertThat(f.getTaste()).isEqualTo("{\"house & techno\":1.0}");
        assertThat(f.getCities()).isEqualTo("[\"" + ev.getVenueCityKey() + "\"]");
        assertThat(f.getFormats()).isEqualTo("[\"club\"]");
        assertThat(f.getNoShowN()).isZero();
        assertThat(f.getAvgGroupSize()).isEqualByComparingTo(new BigDecimal("2.000"));
        assertThat(f.getLastContactFromPersonAt()).isEqualTo(o.getCreatedAt());
        assertThat(f.getLogicVersion()).isEqualTo(logic.logicVersion());
        assertThat(f.getSends30d()).isZero();
        assertThat(f.getUpdatedAt()).isAfter(Instant.now().minus(1, ChronoUnit.HOURS));
    }

    @Test
    void purchase_membershipOfAnotherOrgOnly_writesNothing() {
        FanFeatureFixtures.Org orgA = fx.org("UTC");
        FanFeatureFixtures.Org orgB = fx.org("UTC");
        String email = FanFeatureFixtures.email("elsewhere");
        fx.paidOrder(fx.event(orgA, TECHNO, daysAgo(3)), email, daysAgo(3), Ticket.STATE_ISSUED);
        Membership inB = fx.membership(orgB.id(), email);

        projector().onMembershipProjected(new MembershipProjected(orgA.id(), email));

        assertThat(features.findById(inB.getMembershipId())).isEmpty();
    }

    @Test
    void purchase_unknownEmail_isANoOp() {
        assertThatCode(() -> projector().onMembershipProjected(
                new MembershipProjected(UUID.randomUUID(), FanFeatureFixtures.email("nobody"))))
                .doesNotThrowAnyException();
    }

    // ---- redeem path ----

    @Test
    void redeem_recomputesNoShow() {
        FanFeatureFixtures.Org org = fx.org("UTC");
        String email = FanFeatureFixtures.email("door");
        Event ended = fx.event(org, TECHNO, daysAgo(5));
        Order o = fx.paidOrder(ended, email, daysAgo(6), Ticket.STATE_ISSUED);
        Membership m = fx.membership(org.id(), email);
        FanFeatureProjector p = projector();
        p.recompute(org.id(), m.getMembershipId());
        assertThat(features.findById(m.getMembershipId()).orElseThrow().getNoShowN()).isEqualTo(1);

        Ticket t = tickets.findByOrderId(o.getId()).get(0);
        t.setState(Ticket.STATE_REDEEMED);
        t.setRedeemedAt(Instant.now());
        tickets.save(t);
        p.onTicketRedeemed(new TicketRedeemedEvent(o.getId(), ended.getId()));

        assertThat(features.findById(m.getMembershipId()).orElseThrow().getNoShowN()).isZero();
    }

    @Test
    void redeem_unknownOrder_isANoOp() {
        assertThatCode(() -> projector().onTicketRedeemed(new TicketRedeemedEvent(UUID.randomUUID(), UUID.randomUUID())))
                .doesNotThrowAnyException();
    }

    // ---- consent path ----

    @Test
    void consentChanged_afterObjection_clearsTasteCitiesAndFormats() {
        FanFeatureFixtures.Org org = fx.org("UTC");
        String email = FanFeatureFixtures.email("objector");
        fx.paidOrder(fx.event(org, TECHNO, daysAgo(4)), email, daysAgo(4), Ticket.STATE_ISSUED);
        Membership m = fx.membership(org.id(), email);
        FanFeatureProjector p = projector();
        p.recompute(org.id(), m.getMembershipId());
        assertThat(features.findById(m.getMembershipId()).orElseThrow().getTaste()).isNotEqualTo("{}");

        consentService.unsubscribe(org.id(), m.getMembershipId(), "one_click", "email",
                ConsentOrigin.DATA_SUBJECT, null);
        p.onConsentChanged(new ConsentChanged(org.id(), m.getMembershipId(), false));

        FanFeature f = features.findById(m.getMembershipId()).orElseThrow();
        assertThat(f.getTaste()).isEqualTo("{}");
        assertThat(f.getCities()).isEqualTo("[]");
        assertThat(f.getFormats()).isEqualTo("[]");
        assertThat(f.getPaidOrders()).isEqualTo(1);
    }

    @Test
    void objection_clearsTasteInsideItsOwnTransaction_evenWithTheLiveQueueFull() {
        FanFeatureFixtures.Org org = fx.org("UTC");
        String email = FanFeatureFixtures.email("saturated");
        fx.paidOrder(fx.event(org, TECHNO, daysAgo(4)), email, daysAgo(4), Ticket.STATE_ISSUED);
        Membership m = fx.membership(org.id(), email);
        projector().recompute(org.id(), m.getMembershipId());
        FanFeatureProjector saturated = projector(task -> { throw new RejectedExecutionException("full"); });

        // Read before commit: no after-commit listener has run, so a cleared row proves the clear is inline.
        FanFeature inside = new TransactionTemplate(txManager).execute(s -> {
            consentService.unsubscribe(org.id(), m.getMembershipId(), "one_click", "email",
                    ConsentOrigin.DATA_SUBJECT, null);
            return features.findById(m.getMembershipId()).orElseThrow();
        });
        saturated.onConsentChanged(new ConsentChanged(org.id(), m.getMembershipId(), false));

        assertThat(inside.getTaste()).isEqualTo("{}");
        assertThat(inside.getCities()).isEqualTo("[]");
        assertThat(inside.getFormats()).isEqualTo("[]");
        assertThat(inside.getPaidOrders()).isEqualTo(1);
        FanFeature after = features.findById(m.getMembershipId()).orElseThrow();
        assertThat(after.getTaste()).isEqualTo("{}");
        assertThat(after.getCities()).isEqualTo("[]");
        assertThat(after.getFormats()).isEqualTo("[]");
    }

    @Test
    void operatorUnsubscribe_isNoObjection_andLeavesTasteForTheListener() {
        FanFeatureFixtures.Org org = fx.org("UTC");
        String email = FanFeatureFixtures.email("operator");
        fx.paidOrder(fx.event(org, TECHNO, daysAgo(4)), email, daysAgo(4), Ticket.STATE_ISSUED);
        Membership m = fx.membership(org.id(), email);
        projector().recompute(org.id(), m.getMembershipId());

        FanFeature inside = new TransactionTemplate(txManager).execute(s -> {
            consentService.unsubscribe(org.id(), m.getMembershipId(), "organizer", "email",
                    ConsentOrigin.OPERATOR, null);
            return features.findById(m.getMembershipId()).orElseThrow();
        });

        assertThat(inside.getTaste()).isEqualTo("{\"house & techno\":1.0}");
    }

    // ---- skips ----

    @Test
    void killSwitchOff_writesNothing() {
        FanFeatureFixtures.Org org = fx.org("UTC");
        String email = FanFeatureFixtures.email("off");
        fx.paidOrder(fx.event(org, TECHNO, daysAgo(2)), email, daysAgo(2), Ticket.STATE_ISSUED);
        Membership m = fx.membership(org.id(), email);
        AudiencePlanProperties props = new AudiencePlanProperties();
        props.setEnabled(false);

        assertThat(projector(new AudiencePlanAccess(props)).recompute(org.id(), m.getMembershipId())).isFalse();
        assertThat(features.findById(m.getMembershipId())).isEmpty();
    }

    @Test
    void orgOffNonBlankAllowList_writesNothing() {
        FanFeatureFixtures.Org org = fx.org("UTC");
        Membership m = fx.membership(org.id(), FanFeatureFixtures.email("listed"));
        AudiencePlanProperties props = new AudiencePlanProperties();
        props.setBetaOrgIds(Set.of(UUID.randomUUID()));

        assertThat(projector(new AudiencePlanAccess(props)).recompute(org.id(), m.getMembershipId())).isFalse();
        assertThat(features.findById(m.getMembershipId())).isEmpty();
    }

    @Test
    void erasePendingMembership_isSkipped() {
        FanFeatureFixtures.Org org = fx.org("UTC");
        Membership m = fx.membership(org.id(), FanFeatureFixtures.email("erasing"));
        m.setStatus("erase_pending");
        memberships.save(m);

        assertThat(projector().recompute(org.id(), m.getMembershipId())).isFalse();
        assertThat(features.findById(m.getMembershipId())).isEmpty();
    }

    @Test
    void recompute_wrongOrgForMembership_writesNothing() {
        FanFeatureFixtures.Org org = fx.org("UTC");
        Membership m = fx.membership(org.id(), FanFeatureFixtures.email("wrongorg"));

        assertThat(projector().recompute(UUID.randomUUID(), m.getMembershipId())).isFalse();
        assertThat(features.findById(m.getMembershipId())).isEmpty();
    }

    // ---- batch and write rules ----

    @Test
    void batch_failingTarget_isSkippedAndTheRestAreWritten() {
        FanFeatureFixtures.Org org = fx.org("UTC");
        String email = FanFeatureFixtures.email("good");
        Membership good = fx.membership(org.id(), email);
        Membership bad = fx.membership(org.id(), FanFeatureFixtures.email("bad"));
        FanFeatureRepository failing = mock(FanFeatureRepository.class, AdditionalAnswers.delegatesTo(features));
        doThrow(new IllegalStateException("write failed for bad@example.com"))
                .when(failing).save(argThat(f -> f != null && bad.getMembershipId().equals(f.getMembershipId())));
        FanFeatureProjector p = new FanFeatureProjector(failing, orders, tickets, events, consents, orgs, consumers,
                memberships, new AudiencePlanAccess(new AudiencePlanProperties()), logic, clock, txManager, Runnable::run);

        int written = p.recomputeBatch(List.of(
                new FanFeatureTarget(bad.getMembershipId(), org.id(), bad.getMembershipId() + "@x", false),
                new FanFeatureTarget(good.getMembershipId(), org.id(), email, false)));

        assertThat(written).isEqualTo(1);
        assertThat(features.findById(good.getMembershipId())).isPresent();
        assertThat(features.findById(bad.getMembershipId())).isEmpty();
    }

    @Test
    void batch_membershipGoneBeforeWrite_writesNothing() {
        FanFeatureFixtures.Org org = fx.org("UTC");
        FanFeatureTarget orphan = new FanFeatureTarget(UUID.randomUUID(), org.id(), FanFeatureFixtures.email("orphan"), false);

        assertThat(projector().recomputeBatch(List.of(orphan))).isZero();
        assertThat(features.findById(orphan.membershipId())).isEmpty();
    }

    @Test
    void batch_objectionCommittedAfterPageLoad_writesNoTaste() {
        FanFeatureFixtures.Org org = fx.org("UTC");
        String email = FanFeatureFixtures.email("late-objector");
        fx.paidOrder(fx.event(org, TECHNO, daysAgo(4)), email, daysAgo(4), Ticket.STATE_ISSUED);
        Membership m = fx.membership(org.id(), email);
        // The page was read before the objection committed, so the target still says "not objected".
        FanFeatureTarget stale = features.findTarget(org.id(), m.getMembershipId()).orElseThrow();
        consentService.unsubscribe(org.id(), m.getMembershipId(), "one_click", "email",
                ConsentOrigin.DATA_SUBJECT, null);

        assertThat(projector().recomputeBatch(List.of(stale))).isEqualTo(1);

        FanFeature f = features.findById(m.getMembershipId()).orElseThrow();
        assertThat(stale.objectedProfiling()).isFalse();
        assertThat(f.getTaste()).isEqualTo("{}");
        assertThat(f.getCities()).isEqualTo("[]");
        assertThat(f.getFormats()).isEqualTo("[]");
        assertThat(f.getPaidOrders()).isEqualTo(1);
    }

    @Test
    void batch_erasureCommittedAfterPageLoad_deletesTheRow() {
        FanFeatureFixtures.Org org = fx.org("UTC");
        String email = FanFeatureFixtures.email("late-eraser");
        Membership m = fx.membership(org.id(), email);
        FanFeatureProjector p = projector();
        p.recompute(org.id(), m.getMembershipId());
        FanFeatureTarget stale = features.findTarget(org.id(), m.getMembershipId()).orElseThrow();
        m.setStatus("erase_pending");
        memberships.save(m);

        assertThat(p.recomputeBatch(List.of(stale))).isZero();
        assertThat(features.findById(m.getMembershipId())).isEmpty();
    }

    @Test
    void unreadableOrgTimezone_fallsBackToUtc() {
        FanFeatureFixtures.Org org = fx.org("Not/AZone");
        Membership m = fx.membership(org.id(), FanFeatureFixtures.email("tz"));

        assertThat(projector().zoneOf(org.id())).isEqualTo(ZoneOffset.UTC);
        assertThat(projector().recompute(org.id(), m.getMembershipId())).isTrue();
        assertThat(features.findById(m.getMembershipId()).orElseThrow().getFanClass()).isEqualTo("none");
    }

    @Test
    void orgTimezone_readableIsUsed() {
        assertThat(projector().zoneOf(fx.org("Europe/Paris").id())).isEqualTo(ZoneId.of("Europe/Paris"));
    }

    @Test
    void orgTimezone_blank_fallsBackToUtc() {
        // The column is NOT NULL, so a missing row (below) is the only way to reach a null timezone.
        assertThat(projector().zoneOf(fx.org("  ").id())).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void missingOrganizationRow_fallsBackToUtcAndStillWrites() {
        UUID noSuchOrg = UUID.randomUUID();
        Membership m = fx.membership(noSuchOrg, FanFeatureFixtures.email("orphan-org"));

        assertThat(projector().zoneOf(noSuchOrg)).isEqualTo(ZoneOffset.UTC);
        assertThat(projector().recompute(noSuchOrg, m.getMembershipId())).isTrue();
        assertThat(features.findById(m.getMembershipId()).orElseThrow().getOrgId()).isEqualTo(noSuchOrg);
    }

    @Test
    void existingRow_takesOrgIdFromMembership_andKeepsSends30d() {
        FanFeatureFixtures.Org org = fx.org("UTC");
        Membership m = fx.membership(org.id(), FanFeatureFixtures.email("rewrite"));
        FanFeatureProjector p = projector();
        p.recompute(org.id(), m.getMembershipId());
        jdbc.update("UPDATE fan_features SET org_id = ?, sends_30d = 3 WHERE membership_id = ?",
                UUID.randomUUID(), m.getMembershipId());

        p.recompute(org.id(), m.getMembershipId());

        FanFeature f = features.findById(m.getMembershipId()).orElseThrow();
        assertThat(f.getOrgId()).isEqualTo(org.id());
        assertThat(f.getSends30d()).isEqualTo(3);
    }

    @Test
    void unchangedValues_stillRefreshUpdatedAt() {
        FanFeatureFixtures.Org org = fx.org("UTC");
        Membership m = fx.membership(org.id(), FanFeatureFixtures.email("touch"));
        FanFeatureProjector p = projector();
        p.recompute(org.id(), m.getMembershipId());
        jdbc.update("UPDATE fan_features SET updated_at = ? WHERE membership_id = ?",
                Timestamp.from(daysAgo(2)), m.getMembershipId());

        p.recompute(org.id(), m.getMembershipId());

        assertThat(features.findById(m.getMembershipId()).orElseThrow().getUpdatedAt())
                .isAfter(Instant.now().minus(1, ChronoUnit.HOURS));
    }

    // ---- failures never reach the source transaction ----

    @Test
    void listenerFailures_areSwallowed() {
        FanFeatureRepository broken = mock(FanFeatureRepository.class);
        when(broken.findTarget(any(), any())).thenThrow(new IllegalStateException("db down"));
        OrderRepository brokenOrders = mock(OrderRepository.class);
        when(brokenOrders.findById(any())).thenThrow(new IllegalStateException("db down"));
        ConsumerRepository brokenConsumers = mock(ConsumerRepository.class);
        when(brokenConsumers.findByNormalizedEmail(any())).thenThrow(new IllegalStateException("db down"));
        FanFeatureProjector p = new FanFeatureProjector(broken, brokenOrders, tickets, events, consents, orgs,
                brokenConsumers, memberships, new AudiencePlanAccess(new AudiencePlanProperties()), logic, clock, txManager,
                Runnable::run);

        assertThatCode(() -> p.onConsentChanged(new ConsentChanged(UUID.randomUUID(), UUID.randomUUID(), false)))
                .doesNotThrowAnyException();
        assertThatCode(() -> p.onTicketRedeemed(new TicketRedeemedEvent(UUID.randomUUID(), UUID.randomUUID())))
                .doesNotThrowAnyException();
        assertThatCode(() -> p.onMembershipProjected(new MembershipProjected(UUID.randomUUID(), "x@example.com")))
                .doesNotThrowAnyException();
    }

    @Test
    void redeem_orderWithNullEmail_isANoOp() {
        OrderRepository ordersMock = mock(OrderRepository.class);
        Order noEmail = new Order();
        noEmail.setOrgId(UUID.randomUUID());
        UUID orderId = UUID.randomUUID();
        when(ordersMock.findById(orderId)).thenReturn(Optional.of(noEmail));
        ConsumerRepository consumersMock = mock(ConsumerRepository.class);
        FanFeatureProjector p = new FanFeatureProjector(features, ordersMock, tickets, events, consents, orgs,
                consumersMock, memberships, new AudiencePlanAccess(new AudiencePlanProperties()), logic, clock, txManager,
                Runnable::run);

        assertThatCode(() -> p.onTicketRedeemed(new TicketRedeemedEvent(orderId, UUID.randomUUID())))
                .doesNotThrowAnyException();
        verify(ordersMock).findById(orderId);
        verifyNoInteractions(consumersMock);
    }

    // ---- live dispatch: bounded pool, coalesced per key ----

    @Test
    void consentChanged_repeatsWhileQueued_coalesceIntoOneRun() {
        List<Runnable> queued = new ArrayList<>();
        FanFeatureProjector p = projector(queued::add);
        UUID orgId = UUID.randomUUID();
        UUID mid = UUID.randomUUID();

        p.onConsentChanged(new ConsentChanged(orgId, mid, false));
        p.onConsentChanged(new ConsentChanged(orgId, mid, false));
        p.onConsentChanged(new ConsentChanged(orgId, UUID.randomUUID(), false));

        assertThat(queued).hasSize(2);
    }

    @Test
    void consentChanged_afterTheQueuedRunStarts_queuesAgain() {
        List<Runnable> queued = new ArrayList<>();
        FanFeatureProjector p = projector(queued::add);
        ConsentChanged event = new ConsentChanged(UUID.randomUUID(), UUID.randomUUID(), false);

        p.onConsentChanged(event);
        queued.get(0).run();
        p.onConsentChanged(event);

        assertThat(queued).hasSize(2);
    }

    @Test
    void purchaseAndRedeem_repeatsWhileQueued_coalesce() {
        List<Runnable> queued = new ArrayList<>();
        FanFeatureProjector p = projector(queued::add);
        UUID orgId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        p.onMembershipProjected(new MembershipProjected(orgId, "a@example.com"));
        p.onMembershipProjected(new MembershipProjected(orgId, "a@example.com"));
        p.onTicketRedeemed(new TicketRedeemedEvent(orderId, UUID.randomUUID()));
        p.onTicketRedeemed(new TicketRedeemedEvent(orderId, UUID.randomUUID()));

        assertThat(queued).hasSize(2);
    }

    @Test
    void deferrableConsentChange_isLeftToTheNightlyPass() {
        List<Runnable> queued = new ArrayList<>();

        projector(queued::add).onConsentChanged(new ConsentChanged(UUID.randomUUID(), UUID.randomUUID(), true));

        assertThat(queued).isEmpty();
    }

    @Test
    void fullQueue_dropsWithoutThrowing_andReleasesTheKey() {
        List<Runnable> queued = new ArrayList<>();
        boolean[] full = {true};
        FanFeatureProjector p = projector(task -> {
            if (full[0]) throw new RejectedExecutionException("full");
            queued.add(task);
        });
        ConsentChanged event = new ConsentChanged(UUID.randomUUID(), UUID.randomUUID(), false);

        assertThatCode(() -> p.onConsentChanged(event)).doesNotThrowAnyException();
        full[0] = false;
        p.onConsentChanged(event);

        assertThat(queued).hasSize(1);
    }

    @Test
    void listeners_runAfterCommit_andHandOffInsteadOfUsingTheDefaultPool() {
        for (String name : List.of("onMembershipProjected", "onTicketRedeemed", "onConsentChanged")) {
            var method = java.util.Arrays.stream(FanFeatureProjector.class.getMethods())
                    .filter(mm -> mm.getName().equals(name)).findFirst().orElseThrow();
            assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                    .as(name).isEqualTo(TransactionPhase.AFTER_COMMIT);
            assertThat(method.getAnnotation(Async.class)).as(name).isNull();
        }
    }
}

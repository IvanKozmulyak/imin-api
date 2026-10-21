package com.imin.iminapi.checkoutreminder;

import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.checkoutreminder.dto.CheckoutReminderSummaryDto;
import com.imin.iminapi.checkoutreminder.model.CheckoutReminder;
import com.imin.iminapi.checkoutreminder.repository.CheckoutReminderRepository;
import com.imin.iminapi.checkoutreminder.repository.OrgCheckoutReminderSettingRepository;
import com.imin.iminapi.checkoutreminder.service.CheckoutReminderService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundReason;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.refund.RefundStatus;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.ticket.TicketsIssuedEvent;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/** Only an order for the same night from the same address, within 7 days after the send, is credited. */
@IminIntegrationTest
class CheckoutReminderOutcomeTest {

    private static final Instant SENT = Instant.parse("2026-11-02T11:00:00Z");

    @Autowired CheckoutReminderService service;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired MutableClock clock;
    @Autowired IminFixtures fx;
    @Autowired OrganizationRepository orgs;
    @Autowired EventRepository events;
    @Autowired CheckoutReminderRepository reminders;
    @Autowired OrgCheckoutReminderSettingRepository settings;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired RefundRepository refunds;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("taskExecutor") Executor taskExecutor;
    @Autowired @Qualifier("ticketEmailExecutor") Executor ticketEmailExecutor;

    private ReminderRows rows;
    private ReminderRows.Org org;
    private Event night;
    private TicketTier tier;
    private String email;
    private UUID reminderId;

    @BeforeEach
    void setUp() {
        clock.setInstant(SENT);
        rows = new ReminderRows(fx, orgs, events, reminders, settings, consumers, memberships, jdbc);
        org = rows.org(SENT);
        night = rows.night(org, SENT.plus(Duration.ofDays(20)));
        tier = rows.tier(night, 1600, 50);
        email = rows.email("outcome");
        reminderId = rows.waiting(org, night, tier, email, 2, "en", false,
                SENT.minus(Duration.ofMinutes(61)), SENT.minus(Duration.ofMinutes(1)));
        rows.status(reminderId, "SENT", null, SENT);
    }

    @AfterEach
    void cleanUp() {
        AsyncDrain.drain(taskExecutor);
        AsyncDrain.drain(ticketEmailExecutor);
        rows.deleteOwn();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sentPlus2Days", "sentPlus8Days", "otherNight", "orderBeforeTheSend", "testModeOrder"})
    void onlyAnOrderForThatNightWithin7DaysCounts(String variant) {
        Event other = rows.night(org, SENT.plus(Duration.ofDays(30)));
        Instant orderAt = switch (variant) {
            case "sentPlus2Days", "otherNight", "testModeOrder" -> SENT.plus(Duration.ofDays(2));
            case "sentPlus8Days" -> SENT.plus(Duration.ofDays(8));
            case "orderBeforeTheSend" -> SENT.minus(Duration.ofHours(1));
            default -> throw new IllegalArgumentException(variant);
        };
        clock.setInstant(orderAt);

        Order order = issue(variant.equals("otherNight") ? other : night, variant.equals("testModeOrder"));

        CheckoutReminder after = rows.load(reminderId);
        if (variant.equals("sentPlus2Days")) {
            assertThat(after.getRecoveredOrderId()).isEqualTo(order.getId());
            assertThat(after.getRecoveredAt()).isEqualTo(orderAt);
        } else {
            assertThat(after.getRecoveredOrderId()).isNull();
            assertThat(after.getRecoveredAt()).isNull();
        }
    }

    @Test
    void aFullRefundTakesTheOrderOffTheSales() {
        clock.setInstant(SENT.plus(Duration.ofDays(1)));
        Order order = issue(night, false);
        AuthPrincipal owner = fx.principal(org.owner());

        CheckoutReminderSummaryDto before = service.summary(owner);
        assertThat(before.boughtAfter()).isEqualTo(1);
        assertThat(before.salesMinor()).isEqualTo(3558L);
        assertThat(before.recent().get(0).boughtNetMinor()).isEqualTo(3558L);

        refund(order, 3558L);

        CheckoutReminderSummaryDto after = service.summary(owner);
        assertThat(after.boughtAfter()).isZero();
        assertThat(after.salesMinor()).isZero();
    }

    /** An order as issuance commits it, then the issuance event after that commit. */
    private Order issue(Event e, boolean testMode) {
        return tx.execute(status -> {
            Order o = fx.order(e, email.toUpperCase());
            o.setTotalMinor(3558L);
            o.setTestMode(testMode);
            publisher.publishEvent(new TicketsIssuedEvent(o.getId()));
            return o;
        });
    }

    private void refund(Order o, long amount) {
        Refund r = new Refund();
        r.setOrderId(o.getId());
        r.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        r.setStripeRefundId("re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        r.setAmountMinor(amount);
        r.setCurrency("EUR");
        r.setReason(RefundReason.OTHER);
        r.setStatus(RefundStatus.SUCCEEDED);
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        refunds.save(r);
    }
}

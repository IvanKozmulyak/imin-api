package com.imin.iminapi.service.event;

import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerNotificationPreferenceRepository;
import com.imin.iminapi.buyer.repository.BuyerPushDeviceRepository;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.NotifyRelease;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.push.ExpoPushSender;
import com.imin.iminapi.push.PushProperties;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.ModerationHoldRepository;
import com.imin.iminapi.repository.NotifyReleaseRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Notify-me as a waitlist: every-release rows are re-armed after a sell-out and mailed once per release,
 * ticket holders are skipped, and each release's accepted emails are counted for the organizer.
 *
 * <p>The sender is built by hand (as in {@link NotifyReleaseSenderTest}) so sweeps skip the ShedLock proxy and
 * run on a clock this class moves. {@code @Transactional} rolls every row back.
 */
@IminIntegrationTest
@Transactional
class NotifyRearmTest {

    static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    static final Instant NOTIFIED_AT = NOW.minus(Duration.ofDays(1));

    @Autowired NotifySubscriptionRepository subscriptions;
    @Autowired NotifyReleaseRepository releases;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired OrderRepository orders;
    @Autowired SuppressionRepository suppressions;
    @Autowired EmailTemplateRenderer renderer;
    @Autowired EmailProperties emailProps;
    @Autowired BuyerPushDeviceRepository pushDevices;
    @Autowired BuyerAccountEmailRepository buyerEmails;
    @Autowired BuyerNotificationPreferenceRepository pushPrefs;
    @Autowired UnsubscribeTokenService unsubscribeTokens;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    final MutableClock clock = new MutableClock();
    EmailService email;
    NotifyReleaseSender sender;
    Organization org;
    User owner;

    @BeforeEach
    void setUp() {
        clock.setInstant(NOW);
        email = mock(EmailService.class);
        sender = new NotifyReleaseSender(subscriptions, events, tiers, suppressions, email, renderer, emailProps,
                clock, new PushProperties(), mock(ExpoPushSender.class), pushDevices, buyerEmails, pushPrefs,
                unsubscribeTokens, mock(ModerationHoldRepository.class), releases, new NotifyProperties());
        org = fx.org();
        owner = fx.owner(org);
    }

    // -----------------------------------------------------------------------
    // Re-arm
    // -----------------------------------------------------------------------

    /** Re-armed only after a sell-out, mailed once per release, in the wording of the row's promise. */
    @ParameterizedTest(name = "everyRelease={0}")
    @ValueSource(booleans = {true, false})
    void aReleaseAfterASellOut_mailsEveryReleaseRowsAgain_oncePerRelease(boolean everyRelease) {
        Event e = liveEvent();
        TicketTier t = tier(e, 10, 0, true);
        NotifySubscription sub = subscribe(e, fx.email("fan"), everyRelease);

        sender.sweep();                   // release 1
        setSold(t, 10);
        clock.advance(Duration.ofHours(7));
        sender.sweep();                   // sold out, last email 7 h ago: re-arm
        sender.sweep();                   // still sold out: nothing to send
        setSold(t, 9);
        sender.sweep();                   // a refund: release 2
        sender.sweep();                   // still on sale: no more

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email, times(everyRelease ? 2 : 1)).send(eq(sub.getEmail()), anyString(), anyString(), text.capture());
        assertThat(text.getAllValues()).allSatisfy(body -> assertThat(body).contains(everyRelease
                ? "If they sell out and come back again, we'll email you again, at most once every 6 hours, "
                        + "unless this email address has a ticket by then."
                : "This is a one-time notification."));
        assertThat(notifiedAtOf(sub)).isEqualTo(everyRelease ? NOW.plus(Duration.ofHours(7)) : NOW);
    }

    /** At most one email per 6 h: a row emailed minutes ago waits through a sell-out, an older one is re-armed. */
    @Test
    void aSellOut_reArmsOnlyRowsEmailedAtLeastSixHoursAgo() {
        Event e = liveEvent();
        tier(e, 10, 10, true);
        NotifySubscription recent = notifiedAt(e, fx.email("recent"), NOW.minus(Duration.ofMinutes(5)));
        NotifySubscription older = notifiedAt(e, fx.email("older"), NOW.minus(Duration.ofHours(7)));

        sender.sweep();

        assertThat(notifiedAtOf(recent)).isEqualTo(NOW.minus(Duration.ofMinutes(5)));
        assertThat(notifiedAtOf(older)).isNull();
    }

    enum Holding { LIVE_TICKET, REDEEMED_TICKET, REFUNDED_TICKET, REVOKED_TICKET, TEST_ORDER, OTHER_EVENT, NONE }

    /** Only a live ticket for this event, on a real order with the same address, keeps a row notified. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Holding.class)
    void aSellOut_reArmsEveryReleaseRows_exceptLiveTicketHolders(Holding holding) {
        Event e = liveEvent();
        tier(e, 10, 10, true);
        NotifySubscription sub = notified(e, fx.email("fan"), true);
        // The buyer typed the address in another case at checkout.
        String atCheckout = sub.getEmail().toUpperCase(Locale.ROOT);
        switch (holding) {
            case LIVE_TICKET -> fx.ticket(fx.order(e, atCheckout), Ticket.STATE_ISSUED);
            case REDEEMED_TICKET -> fx.ticket(fx.order(e, atCheckout), Ticket.STATE_REDEEMED);
            case REFUNDED_TICKET -> fx.ticket(fx.order(e, atCheckout), Ticket.STATE_REFUNDED);
            case REVOKED_TICKET -> fx.ticket(fx.order(e, atCheckout), Ticket.STATE_REVOKED);
            case TEST_ORDER -> {
                Order o = fx.order(e, atCheckout);
                o.setTestMode(true);
                fx.ticket(orders.save(o), Ticket.STATE_ISSUED);
            }
            case OTHER_EVENT -> fx.ticket(fx.order(liveEvent(), atCheckout), Ticket.STATE_ISSUED);
            case NONE -> { }
        }

        sender.sweep();

        boolean holds = holding == Holding.LIVE_TICKET || holding == Holding.REDEEMED_TICKET;
        assertThat(notifiedAtOf(sub)).isEqualTo(holds ? NOTIFIED_AT : null);
        verifyNoInteractions(email);
    }

    enum Stock { HELD_BY_CHECKOUTS, EVERY_TIER_DISABLED, DISABLED_TIER_WITH_STOCK, UNPUBLISHED, CANCELLED, PRIVATE, DELETED }

    /** "Sold out" is every enabled tier sold on a listed event; holds and disabled tiers do not count. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Stock.class)
    void reArmWaitsForEveryEnabledTierToBeSold(Stock stock) {
        Event e = liveEvent();
        switch (stock) {
            case HELD_BY_CHECKOUTS -> {
                TicketTier t = tier(e, 10, 8, true);
                t.setReserved(2);
                tiers.save(t);
            }
            case EVERY_TIER_DISABLED -> tier(e, 10, 0, false);
            case DISABLED_TIER_WITH_STOCK -> {
                tier(e, 10, 10, true);
                tier(e, 10, 0, false);
            }
            case UNPUBLISHED -> {
                tier(e, 10, 10, true);
                e.setPublishedAt(null);
                e = events.save(e);
            }
            case CANCELLED -> {
                tier(e, 10, 10, true);
                e.setStatus(EventStatus.CANCELLED);
                e = events.save(e);
            }
            case PRIVATE -> {
                tier(e, 10, 10, true);
                e.setVisibility(EventVisibility.PRIVATE);
                e = events.save(e);
            }
            case DELETED -> {
                tier(e, 10, 10, true);
                events.flush();
                // deleted_at is insert-only on the entity; production writes it with a bulk update too.
                jdbc.update("UPDATE events SET deleted_at = ? WHERE id = ?", Timestamp.from(NOW), e.getId());
            }
        }
        NotifySubscription sub = notified(e, fx.email("fan"), true);

        sender.sweep();

        assertThat(notifiedAtOf(sub)).isEqualTo(stock == Stock.DISABLED_TIER_WITH_STOCK ? null : NOTIFIED_AT);
    }

    /** A pending address that already holds a live ticket is marked without an email and not counted. */
    @Test
    void aPendingTicketHolder_isMarkedWithoutAnEmail() {
        Event e = liveEvent();
        tier(e, 10, 0, true);
        NotifySubscription holder = subscribe(e, fx.email("holder"), true);
        NotifySubscription fan = subscribe(e, fx.email("fan"), true);
        fx.ticket(fx.order(e, holder.getEmail()), Ticket.STATE_ISSUED);

        sender.sweep();

        verify(email, never()).send(eq(holder.getEmail()), anyString(), anyString(), anyString());
        verify(email, times(1)).send(eq(fan.getEmail()), anyString(), anyString(), anyString());
        assertThat(notifiedAtOf(holder)).isEqualTo(NOW);
        assertThat(latestRelease(e).getSentCount()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Release counts
    // -----------------------------------------------------------------------

    /** A release gathers its retries; a sell-out closes it, so the next release counts on its own row. */
    @Test
    void eachReleaseCountsTheEmailsItSent() {
        Event e = liveEvent();
        TicketTier t = tier(e, 10, 0, true);
        NotifySubscription a = subscribe(e, fx.email("a"), true);
        NotifySubscription b = subscribe(e, fx.email("b"), true);
        doThrow(new IllegalStateException("resend down"))
                .when(email).send(eq(b.getEmail()), anyString(), anyString(), anyString());

        sender.sweep();                                   // a accepted, b left pending
        assertThat(latestRelease(e).getSentCount()).isEqualTo(1);
        reset(email);
        clock.advance(Duration.ofMinutes(1));
        sender.sweep();                                   // b accepted on the retry

        NotifyRelease first = latestRelease(e);
        assertThat(first.getSentCount()).isEqualTo(2);
        assertThat(first.getFirstSentAt()).isEqualTo(NOW);
        assertThat(first.getLastSentAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(first.getClosedAt()).isNull();

        setSold(t, 10);
        clock.advance(Duration.ofHours(7));
        sender.sweep();                                   // sold out: release over, both re-armed
        setSold(t, 9);
        clock.advance(Duration.ofMinutes(10));
        sender.sweep();                                   // release 2 mails both again

        NotifyRelease second = latestRelease(e);
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getSentCount()).isEqualTo(2);
        assertThat(second.getFirstSentAt()).isEqualTo(NOW.plus(Duration.ofHours(7)).plus(Duration.ofMinutes(11)));
        assertThat(releases.findById(first.getId()).orElseThrow().getClosedAt())
                .isEqualTo(NOW.plus(Duration.ofHours(7)).plus(Duration.ofMinutes(1)));
    }

    /** One-shot rows are never re-armed, so a newcomer's pending row is what sees the event close its release. */
    @Test
    void aNewcomerWaitingThroughASellOut_startsANewReleaseRow() {
        Event e = liveEvent();
        TicketTier t = tier(e, 10, 0, true);
        subscribe(e, fx.email("early"), false);
        sender.sweep();                                   // release 1: one email
        NotifyRelease first = latestRelease(e);

        setSold(t, 10);
        subscribe(e, fx.email("late"), false);
        clock.advance(Duration.ofMinutes(5));
        sender.sweep();                                   // nothing purchasable: release 1 closes
        setSold(t, 9);
        clock.advance(Duration.ofMinutes(5));
        sender.sweep();                                   // release 2: the newcomer

        NotifyRelease second = latestRelease(e);
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getSentCount()).isEqualTo(1);
        assertThat(second.getFirstSentAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(releases.findById(first.getId()).orElseThrow().getSentCount()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private Event liveEvent() {
        Event e = fx.event(org, owner, EventStatus.LIVE, NOW.plus(Duration.ofDays(10)));
        e.setPublishedAt(NOW.minus(Duration.ofDays(5)));
        return events.save(e);
    }

    private TicketTier tier(Event e, int quantity, int sold, boolean enabled) {
        TicketTier t = fx.tier(e, 2500, quantity);
        t.setSold(sold);
        t.setEnabled(enabled);
        return tiers.save(t);
    }

    private void setSold(TicketTier t, int sold) {
        TicketTier fresh = tiers.findById(t.getId()).orElseThrow();
        fresh.setSold(sold);
        tiers.saveAndFlush(fresh);
    }

    private NotifySubscription subscribe(Event e, String address, boolean everyRelease) {
        NotifySubscription s = new NotifySubscription();
        s.setEventId(e.getId());
        s.setEmail(address);
        s.setEveryRelease(everyRelease);
        return subscriptions.saveAndFlush(s);
    }

    private NotifySubscription notified(Event e, String address, boolean everyRelease) {
        NotifySubscription s = new NotifySubscription();
        s.setEventId(e.getId());
        s.setEmail(address);
        s.setEveryRelease(everyRelease);
        s.setNotifiedAt(NOTIFIED_AT);
        return subscriptions.saveAndFlush(s);
    }

    private NotifySubscription notifiedAt(Event e, String address, Instant at) {
        NotifySubscription s = notified(e, address, true);
        s.setNotifiedAt(at);
        return subscriptions.saveAndFlush(s);
    }

    private Instant notifiedAtOf(NotifySubscription sub) {
        return subscriptions.findById(sub.getId()).orElseThrow().getNotifiedAt();
    }

    private NotifyRelease latestRelease(Event e) {
        return releases.findTopByEventIdOrderByFirstSentAtDesc(e.getId()).orElseThrow();
    }
}

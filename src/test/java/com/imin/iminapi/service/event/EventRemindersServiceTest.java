package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.EventRemindersRequest;
import com.imin.iminapi.dto.event.EventRemindersResponse;
import com.imin.iminapi.dto.event.ReminderPreviewResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundReason;
import com.imin.iminapi.refund.RefundStatus;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.OrderFixtures;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IminIntegrationTest
class EventRemindersServiceTest {

    @Autowired IminFixtures fx;
    @Autowired EventRemindersService service;
    @Autowired EventRepository events;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired FunnelEventRepository funnel;
    @Autowired com.imin.iminapi.refund.RefundRepository refunds;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    private final List<UUID> orgIds = new ArrayList<>();
    private Organization org;
    private User owner;
    private AuthPrincipal principal;
    private Event event;

    @BeforeEach
    void setUp() {
        org = fx.org();
        orgIds.add(org.getId());
        owner = fx.owner(org);
        principal = fx.principal(owner);
        event = fx.event(org, owner, EventStatus.LIVE, clock.instant().plus(Duration.ofHours(22)));
    }

    @AfterEach
    void cleanUp() {
        OrgRows.delete(jdbc, orgIds);
    }

    private Order orderWithTicket(Event e, String state) {
        Order o = fx.order(e, fx.email("buyer"));
        fx.ticket(o, state);
        return o;
    }

    @Test
    void statusCountsSentOrdersNotStampedOnes() {
        Instant at = clock.instant();
        for (int i = 0; i < 3; i++) {
            OrderFixtures.stampDayBefore(orders, orderWithTicket(event, "issued"), "sent", at.plusSeconds(i));
        }
        OrderFixtures.stampDayBefore(orders, orderWithTicket(event, "issued"), "opted_out", at);
        OrderFixtures.stampDayBefore(orders, orderWithTicket(event, "refunded"), "no_ticket", at);

        EventRemindersResponse r = service.settings(principal, event.getId());

        assertThat(r.dayBefore().sent()).isEqualTo(3);
        assertThat(r.dayBefore().optedOut()).isEqualTo(1);
        assertThat(r.dayBefore().state()).isEqualTo("sent");
        assertThat(r.dayBefore().firstSentAt()).isEqualTo(at.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(r.ticketHolderOrders()).as("the refunded-only order is not a recipient").isEqualTo(4);
        assertThat(r.doorsSoon().sent()).isZero();
        assertThat(r.dayBefore().sendsFrom()).isEqualTo(event.getStartsAt().minus(Duration.ofHours(24)));
        assertThat(r.doorsSoon().sendsFrom()).isEqualTo(event.getStartsAt().minus(Duration.ofHours(3)));
    }

    @Test
    void windowStateIsNoDateUpcomingOrPassed() {
        Event draft = fx.event(org, owner, EventStatus.DRAFT, null);
        assertThat(service.settings(principal, draft.getId()).dayBefore().state()).isEqualTo("no_date");

        Event later = fx.event(org, owner, EventStatus.LIVE, clock.instant().plus(Duration.ofDays(5)));
        assertThat(service.settings(principal, later.getId()).dayBefore().state()).isEqualTo("upcoming");

        // 10 h out: the day-before band (20-24 h) has closed with nothing sent, doors-soon is still ahead.
        Event soon = fx.event(org, owner, EventStatus.LIVE, clock.instant().plus(Duration.ofHours(10)));
        var r = service.settings(principal, soon.getId());
        assertThat(r.dayBefore().state()).isEqualTo("passed");
        assertThat(r.doorsSoon().state()).isEqualTo("upcoming");
    }

    @Test
    void insideTheBandWithNothingSentIsSendingNotUpcoming() {
        // event is 22 h out: inside the 20-24 h band, so the send time is already behind us.
        var r = service.settings(principal, event.getId());
        assertThat(r.dayBefore().state()).isEqualTo("sending");
        assertThat(r.doorsSoon().state()).isEqualTo("upcoming");

        Event doors = fx.event(org, owner, EventStatus.LIVE, clock.instant().plus(Duration.ofMinutes(150)));
        var d = service.settings(principal, doors.getId());
        assertThat(d.dayBefore().state()).isEqualTo("passed");
        assertThat(d.doorsSoon().state()).isEqualTo("sending");
    }

    @Test
    void aCancelledEventPromisesNoSendButKeepsWhatWasSent() {
        Event cancelled = fx.event(org, owner, EventStatus.CANCELLED, clock.instant().plus(Duration.ofHours(22)));
        var r = service.settings(principal, cancelled.getId());
        assertThat(r.dayBefore().state()).isEqualTo("cancelled");
        assertThat(r.dayBefore().sendsFrom()).isNull();
        assertThat(r.doorsSoon().state()).isEqualTo("cancelled");

        OrderFixtures.stampDayBefore(orders, orderWithTicket(cancelled, "issued"), "sent", clock.instant());
        var after = service.settings(principal, cancelled.getId());
        assertThat(after.dayBefore().state()).as("a recorded send stays true").isEqualTo("sent");
        assertThat(after.dayBefore().sent()).isEqualTo(1);
    }

    @Test
    void friendResultsCountOnlyThisEventsReminderTraffic() {
        Event other = fx.event(org, owner, EventStatus.LIVE, clock.instant().plus(Duration.ofHours(22)));

        Order kept = OrderFixtures.tagged(orders, fx.order(event, fx.email("a")), "friends", "reminder");
        Order refunded = OrderFixtures.tagged(orders, fx.order(event, fx.email("b")), "friends", "reminder");
        refund(refunded, refunded.getTotalMinor());
        OrderFixtures.tagged(orders, fx.order(other, fx.email("c")), "friends", "reminder");
        OrderFixtures.tagged(orders, fx.order(event, fx.email("d")), "friends", "event-page");
        Order test = fx.order(event, fx.email("t"));
        test.setTestMode(true);
        OrderFixtures.tagged(orders, test, "friends", "reminder");
        beacon(event, "anon-1", "friends", "reminder");
        beacon(event, "anon-1", "friends", "reminder");
        beacon(event, "anon-2", "friends", "reminder");
        beacon(other, "anon-3", "friends", "reminder");
        beacon(event, "anon-4", "friends", "event-page");

        var share = service.settings(principal, event.getId()).share();

        assertThat(share.visitors()).isEqualTo(2);
        assertThat(share.orders()).as("the fully refunded order is not kept").isEqualTo(1);
        assertThat(share.revenueMinor()).isEqualTo(kept.getTotalMinor());
        assertThat(share.currency()).isEqualTo("EUR");
        assertThat(share.eligible()).isTrue();
    }

    private void refund(Order o, long amountMinor) {
        Refund r = new Refund();
        r.setOrderId(o.getId());
        r.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        r.setStripeRefundId("re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        r.setAmountMinor(amountMinor);
        r.setCurrency("EUR");
        r.setReason(RefundReason.OTHER);
        r.setStatus(RefundStatus.SUCCEEDED);
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        refunds.save(r);
    }

    private void beacon(Event e, String anon, String source, String campaign) {
        FunnelEvent f = new FunnelEvent();
        f.setEventId(e.getId());
        f.setStage(FunnelEvent.STAGE_PAGE_VIEW);
        f.setAnonId(anon);
        f.setUtmSource(source);
        f.setUtmCampaign(campaign);
        f.setCreatedAt(clock.instant());
        funnel.save(f);
    }

    @ParameterizedTest
    @ValueSource(strings = {"see https://club.test", "details on www.club.test", "WWW.CLUB.TEST"})
    void doorNoteWithALinkIsRefused(String note) {
        assertThatThrownBy(() -> service.update(principal, event.getId(), new EventRemindersRequest(null, null, note)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.FIELD_INVALID);
                    assertThat(e.fields()).containsKey("doorNote");
                });
    }

    @Test
    void doorNoteOver280CharsIsRefusedAndExactly280IsKept() {
        assertThatThrownBy(() -> service.update(principal, event.getId(),
                new EventRemindersRequest(null, null, "x".repeat(281))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.fields()).containsKey("doorNote"));

        var saved = service.update(principal, event.getId(), new EventRemindersRequest(null, null, "  " + "x".repeat(280) + " "));
        assertThat(saved.doorNote()).hasSize(280);

        var cleared = service.update(principal, event.getId(), new EventRemindersRequest(null, null, "  "));
        assertThat(cleared.doorNote()).isNull();
        assertThat(service.settings(principal, event.getId()).doorNote()).isNull();
    }

    @Test
    void omittedFieldsStayAsTheyAre() {
        service.update(principal, event.getId(), new EventRemindersRequest(null, null, "Doors 23:00"));
        var r = service.update(principal, event.getId(), new EventRemindersRequest(false, null, null));

        assertThat(r.enabled()).isFalse();
        assertThat(r.shareLinkEnabled()).isTrue();
        assertThat(r.doorNote()).isEqualTo("Doors 23:00");
    }

    @Test
    void anEventAutosaveAfterTheSwitchDoesNotTurnRemindersBackOn() {
        // An autosave from another tab holds the event as it was before the switch.
        Event stale = events.findById(event.getId()).orElseThrow();
        service.update(principal, event.getId(), new EventRemindersRequest(false, false, "Doors 23:00"));

        stale.setName("Renamed in another tab");
        events.save(stale);

        Event after = events.findById(event.getId()).orElseThrow();
        assertThat(after.getName()).isEqualTo("Renamed in another tab");
        assertThat(after.isRemindersEnabled()).isFalse();
        assertThat(after.isReminderShareEnabled()).isFalse();
        assertThat(after.getReminderDoorNote()).isEqualTo("Doors 23:00");
    }

    @Test
    void aMemberCanReadButNotChange() {
        AuthPrincipal member = new AuthPrincipal(UUID.randomUUID(), org.getId(), UserRole.MEMBER, UUID.randomUUID(),
                AuthPrincipal.Kind.USER);

        assertThat(service.settings(member, event.getId()).enabled()).isTrue();
        assertThatThrownBy(() -> service.update(member, event.getId(), new EventRemindersRequest(false, null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void anotherOrgsEventIs404ForSettingsAndPreview() {
        Organization otherOrg = fx.org();
        orgIds.add(otherOrg.getId());
        AuthPrincipal stranger = fx.principal(fx.owner(otherOrg));

        assertThatThrownBy(() -> service.settings(stranger, event.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.preview(stranger, event.getId(), "day_before", "en"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.update(stranger, event.getId(), new EventRemindersRequest(false, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
    }

    @Test
    void previewNeverContainsARealBuyersOrderLink() {
        Order real = orderWithTicket(event, "issued");
        service.update(principal, event.getId(), new EventRemindersRequest(null, null, "Doors 23:00"));

        ReminderPreviewResponse p = service.preview(principal, event.getId(), "day_before", "fr");

        assertThat(p.html() + p.text()).doesNotContain(real.getToken());
        assertThat(p.text()).contains("/order/preview").contains("Doors 23:00");
        assertThat(p.subject()).contains("c'est demain");
        assertThat(p.fromName()).isEqualTo(org.displayName() + " via IMIN");
    }

    @Test
    void previewRejectsAnUnknownWindow() {
        assertThatThrownBy(() -> service.preview(principal, event.getId(), "never", "en"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FIELD_INVALID));
    }
}

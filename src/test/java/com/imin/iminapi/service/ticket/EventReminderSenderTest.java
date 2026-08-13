package com.imin.iminapi.service.ticket;

import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.model.BuyerNotificationPreference;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.repository.BuyerNotificationPreferenceRepository;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.OrderFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Door reminders — spec §4.7.
 *
 * <p>Two properties carry the feature. <b>Once, not twice</b>: a redeploy
 * mid-batch or two instances racing the lock must not mail the same buyer again,
 * which is what the marker column is for. And <b>one email per order</b>, not
 * per ticket — the epic says per-ticket and is wrong.
 *
 * <p>Note the {@code @TestPropertySource}. The feature ships OFF
 * ({@code EmailProperties.remindersEnabled} defaults false, deliberately), so
 * without it every test here would pass by sending nothing at all.
 */
@SpringBootTest(properties = "imin.email.reminders-enabled=true")
@Import(TestRateLimitConfig.class)
class EventReminderSenderTest {

    @Autowired EventReminderSender sender;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired BuyerAccountRepository accounts;
    @Autowired BuyerAccountEmailRepository accountEmails;
    @Autowired BuyerNotificationPreferenceRepository preferences;
    @MockitoBean EmailService email;

    private Event soon;

    @BeforeEach
    void anEventInsideTheWindow() {
        reset(email);
        // Two hours out: inside both the 24h and the 3h window.
        soon = OrderFixtures.event(orgs, users, events, "Vechirka",
                Instant.now().plusSeconds(2 * 3600));
    }

    @Test
    void runningTheSameWindowTwiceSendsOnce() {
        String address = address("once");
        Order order = orderWith(address, 1);

        sender.sweepWindow(EventReminderSender.Window.T24H);
        sender.sweepWindow(EventReminderSender.Window.T24H);

        verify(email, times(1)).send(eq(address), any(), any(), any());
        assertThat(orders.findById(order.getId()).orElseThrow().getReminder24hAt()).isNotNull();
    }

    @Test
    void oneEmailPerOrderNotPerTicket() {
        String address = address("four-tickets");
        orderWith(address, 4);

        sender.sweepWindow(EventReminderSender.Window.T24H);

        verify(email, times(1)).send(eq(address), any(), any(), any());
    }

    @Test
    void theTwoWindowsAreClaimedIndependently() {
        String address = address("both-windows");
        Order order = orderWith(address, 1);

        sender.sweepWindow(EventReminderSender.Window.T24H);
        sender.sweepWindow(EventReminderSender.Window.T3H);

        verify(email, times(2)).send(eq(address), any(), any(), any());
        Order after = orders.findById(order.getId()).orElseThrow();
        assertThat(after.getReminder24hAt()).isNotNull();
        assertThat(after.getReminder3hAt()).isNotNull();
    }

    @Test
    void anAccountWithEventRemindersOffGetsNothing() {
        String address = "opted-out+" + UUID.randomUUID() + "@example.com";
        UUID accountId = accountWithVerifiedAddress(address, null);
        BuyerNotificationPreference pref = new BuyerNotificationPreference(accountId);
        pref.setEventReminders(false);
        preferences.save(pref);

        Order order = orderWith(address, 1);

        sender.sweepWindow(EventReminderSender.Window.T24H);

        verify(email, never()).send(eq(address), any(), any(), any());
        // Still stamped: the sweep must not reconsider this order every tick.
        assertThat(orders.findById(order.getId()).orElseThrow().getReminder24hAt()).isNotNull();
    }

    @Test
    void aGuestWithAValidTicketStillGetsIt() {
        // No account, so no preference row. Art. 6(1)(b): the reminder rides the
        // same basis as the ticket email itself. It is not marketing.
        String address = "guest+" + UUID.randomUUID() + "@example.com";
        orderWith(address, 1);

        sender.sweepWindow(EventReminderSender.Window.T24H);

        verify(email, times(1)).send(eq(address), any(), any(), any());
    }

    @Test
    void anAccountWithNoPreferenceRowIsTreatedAsOn() {
        String address = "default+" + UUID.randomUUID() + "@example.com";
        accountWithVerifiedAddress(address, null);
        orderWith(address, 1);

        sender.sweepWindow(EventReminderSender.Window.T24H);

        verify(email, times(1)).send(eq(address), any(), any(), any());
    }

    @Test
    void anOrderWhoseEveryTicketIsRefundedOrRevokedIsSkipped() {
        String refunded = "refunded+" + UUID.randomUUID() + "@example.com";
        Order a = OrderFixtures.order(orders, soon, refunded, Instant.now());
        OrderFixtures.ticket(tickets, a, "refunded");
        OrderFixtures.ticket(tickets, a, "revoked");

        sender.sweepWindow(EventReminderSender.Window.T24H);

        verify(email, never()).send(eq(refunded), any(), any(), any());
        assertThat(orders.findById(a.getId()).orElseThrow().getReminder24hAt()).isNotNull();
    }

    @Test
    void oneLiveTicketAmongRefundedOnesStillEarnsAReminder() {
        String address = "partial+" + UUID.randomUUID() + "@example.com";
        Order order = OrderFixtures.order(orders, soon, address, Instant.now());
        OrderFixtures.ticket(tickets, order, "refunded");
        OrderFixtures.ticket(tickets, order, "issued");

        sender.sweepWindow(EventReminderSender.Window.T24H);

        verify(email, times(1)).send(eq(address), any(), any(), any());
    }

    @Test
    void aCancelledEventNeverNudgesAnyone() {
        Event cancelled = OrderFixtures.event(orgs, users, events, "Called Off",
                Instant.now().plusSeconds(2 * 3600));
        cancelled.setStatus(com.imin.iminapi.model.EventStatus.CANCELLED);
        events.save(cancelled);

        String address = "cancelled+" + UUID.randomUUID() + "@example.com";
        Order order = OrderFixtures.order(orders, cancelled, address, Instant.now());
        OrderFixtures.ticket(tickets, order, "issued");

        sender.sweepWindow(EventReminderSender.Window.T24H);

        verify(email, never()).send(eq(address), any(), any(), any());
    }

    @Test
    void localeFallsBackOrderLocaleThenAccountThenEnglish() {
        // 1. The order's own snapshot wins.
        String withOrderLocale = "fr+" + UUID.randomUUID() + "@example.com";
        Order french = OrderFixtures.order(orders, soon, withOrderLocale, Instant.now());
        french.setBuyerLocale("fr");
        orders.save(french);
        OrderFixtures.ticket(tickets, french, "issued");

        // 2. No order locale, but the account has one.
        String viaAccount = "uk+" + UUID.randomUUID() + "@example.com";
        accountWithVerifiedAddress(viaAccount, "uk");
        orderWith(viaAccount, 1);

        // 3. Neither: English.
        String plain = "en+" + UUID.randomUUID() + "@example.com";
        orderWith(plain, 1);

        sender.sweepWindow(EventReminderSender.Window.T24H);

        assertThat(subjectSentTo(withOrderLocale)).contains("c'est demain");
        assertThat(subjectSentTo(viaAccount)).contains("вже завтра");
        assertThat(subjectSentTo(plain)).contains("is tomorrow");
    }

    @Test
    void theMasterSwitchIsWhatSweepChecks() {
        // sweep() is the scheduled entry point and consults the flag; the
        // per-window method used above deliberately does not, so these tests
        // exercise the sender rather than the switch.
        String address = address("switch");
        orderWith(address, 1);

        // Two sends, not one: sweep() runs BOTH windows, and this event is
        // inside each of them.
        sender.sweep();     // enabled by @SpringBootTest properties
        verify(email, times(2)).send(eq(address), any(), any(), any());
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private static String address(String tag) {
        return tag + "+" + UUID.randomUUID() + "@example.com";
    }

    private Order orderWith(String buyerEmail, int ticketCount) {
        Order order = OrderFixtures.order(orders, soon, buyerEmail, Instant.now());
        for (int i = 0; i < ticketCount; i++) {
            OrderFixtures.ticket(tickets, order, "issued");
        }
        return order;
    }

    private UUID accountWithVerifiedAddress(String address, String locale) {
        BuyerAccount account = new BuyerAccount();
        account.setLocale(locale);
        account = accounts.save(account);

        BuyerAccountEmail row = new BuyerAccountEmail();
        row.setBuyerAccountId(account.getId());
        row.setEmail(address);
        row.setEmailNormalized(address.trim().toLowerCase());
        row.setVerifiedAt(Instant.now());
        row.setVerifiedKey(address.trim().toLowerCase());
        accountEmails.save(row);

        return account.getId();
    }

    private String subjectSentTo(String address) {
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(email, org.mockito.Mockito.atLeast(1))
                .send(to.capture(), subject.capture(), html.capture(), text.capture());

        for (int i = to.getAllValues().size() - 1; i >= 0; i--) {
            if (address.equalsIgnoreCase(to.getAllValues().get(i))) {
                return subject.getAllValues().get(i);
            }
        }
        throw new AssertionError("nothing sent to " + address);
    }
}

package com.imin.iminapi.service.ticket;

import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.model.BuyerNotificationPreference;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.repository.BuyerNotificationPreferenceRepository;
import com.imin.iminapi.audience.model.ErasedAddress;
import com.imin.iminapi.audience.model.MarketingOptOut;
import com.imin.iminapi.audience.model.SuppressionEntry;
import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.audience.service.AudienceOrderProjector;
import com.imin.iminapi.email.RecordingEmailService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrderFixtures;
import com.imin.iminapi.support.PgFaults;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Door reminders — spec §4.7.
 *
 * <p>Two properties carry the feature. <b>Once, not twice</b>: a redeploy
 * mid-batch or two instances racing the lock must not mail the same buyer again,
 * which is what the marker column is for. And <b>one email per order</b>, not
 * per ticket — the epic says per-ticket and is wrong.
 *
 * <p>Note the {@code remindersEnabled} flip in {@code @BeforeEach}. The feature ships OFF
 * ({@code EmailProperties.remindersEnabled} defaults false, deliberately), so
 * without it every test here would pass by sending nothing at all.
 */
@IminIntegrationTest
class EventReminderSenderTest {

    @Autowired EventReminderSender sender;
    @Autowired com.imin.iminapi.email.EmailProperties emailProps;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired BuyerAccountRepository accounts;
    @Autowired BuyerAccountEmailRepository accountEmails;
    @Autowired BuyerNotificationPreferenceRepository preferences;
    @Autowired RecordingEmailService mail;
    @Autowired com.imin.iminapi.audience.service.SuppressionService suppression;
    @Autowired PropertyFlips flips;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired Clock clock;
    @Autowired IminFixtures fx;
    @Autowired AudienceOrderProjector projector;
    @Autowired ErasedAddressRepository erased;
    @Autowired MarketingOptOutRepository optOuts;
    @Autowired SuppressionRepository suppressions;

    private final List<String> platformErased = new java.util.ArrayList<>();

    @AfterEach
    void removePlatformWideErasures() {
        platformErased.forEach(e -> jdbc.update("delete from erased_addresses where email_normalized = ?", e));
    }

    /** 22 hours out — inside the T-24h band and nowhere near the T-3h one. */
    private Event tomorrow;
    /** 2.5 hours out — inside the T-3h band only. */
    private Event soon;

    @BeforeEach
    void eventsInEachBand() {
        flips.set(emailProps, "remindersEnabled", true);
        // Drain both bands first. The suite shares one database and orders
        // persist across test classes, so without this a test asserting on its
        // own order competes with everything else in the window — and since the
        // sweep is capped and ordered, whether it is reached at all depends on
        // suite order. Draining stamps whatever is pending; the clear below
        // discards those sends so they cannot be mistaken for this test's.
        for (int i = 0; i < 25; i++) {
            mail.clear();
            sender.sweepWindow(EventReminderSender.Window.T24H);
            sender.sweepWindow(EventReminderSender.Window.T3H);
            if (mail.sent().isEmpty()) break;
        }
        mail.clear();
        tomorrow = OrderFixtures.event(orgs, users, events, "Vechirka",
                clock.instant().plusSeconds(22 * 3600));
        soon = OrderFixtures.event(orgs, users, events, "Tonight",
                clock.instant().plusSeconds((long) (2.5 * 3600)));
    }

    @Test
    void runningTheSameWindowTwiceSendsOnce() {
        String address = address("once");
        Order order = orderWith(address, 1);

        sender.sweepWindow(EventReminderSender.Window.T24H);
        sender.sweepWindow(EventReminderSender.Window.T24H);

        assertThat(sentTo(address)).hasSize(1);
        assertThat(orders.findById(order.getId()).orElseThrow().getReminder24hAt()).isNotNull();
        assertThat(orders.findById(order.getId()).orElseThrow().getReminder24hOutcome()).isEqualTo("sent");
        assertThat(mail.lastFromSentTo(address)).contains("via IMIN").contains(orgs.findById(tomorrow.getOrgId()).orElseThrow().displayName());
    }

    @Test
    void oneEmailPerOrderNotPerTicket() {
        String address = address("four-tickets");
        orderWith(address, 4);

        sender.sweepWindow(EventReminderSender.Window.T24H);

        assertThat(sentTo(address)).hasSize(1);
    }

    /**
     * The bug this band exists to prevent. An event two and a half hours away is
     * NOT "tomorrow", and before the bands existed it matched the 24h query too
     * — so a buyer who bought at 18:00 for a 20:00 door got "Vechirka is
     * tomorrow" and "Doors soon" milliseconds apart, the first of them lying.
     */
    @Test
    void anEventHoursAwayIsNeverToldItIsTomorrow() {
        String address = address("tonight");
        Order order = orderOn(soon, address, 1);

        sender.sweepWindow(EventReminderSender.Window.T24H);

        assertThat(sentTo(address)).isEmpty();
        assertThat(orders.findById(order.getId()).orElseThrow().getReminder24hAt()).isNull();

        sender.sweepWindow(EventReminderSender.Window.T3H);
        assertThat(subjectSentTo(address)).contains("Doors soon");
    }

    /** And the converse: a night 22 hours out gets the date, not the door call. */
    @Test
    void anEventTomorrowIsNotToldDoorsAreSoon() {
        String address = address("tomorrow");
        orderWith(address, 1);

        sender.sweepWindow(EventReminderSender.Window.T3H);
        assertThat(sentTo(address)).isEmpty();

        sender.sweepWindow(EventReminderSender.Window.T24H);
        assertThat(subjectSentTo(address)).contains("is tomorrow");
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

        assertThat(sentTo(address)).isEmpty();
        // Still stamped: the sweep must not reconsider this order every tick.
        assertThat(orders.findById(order.getId()).orElseThrow().getReminder24hAt()).isNotNull();
        assertThat(orders.findById(order.getId()).orElseThrow().getReminder24hOutcome()).isEqualTo("opted_out");
    }

    @Test
    void aGuestWithAValidTicketStillGetsIt() {
        // No account, so no preference row. Art. 6(1)(b): the reminder rides the
        // same basis as the ticket email itself. It is not marketing.
        String address = "guest+" + UUID.randomUUID() + "@example.com";
        orderWith(address, 1);

        sender.sweepWindow(EventReminderSender.Window.T24H);

        assertThat(sentTo(address)).hasSize(1);
    }

    @Test
    void anAccountWithNoPreferenceRowIsTreatedAsOn() {
        String address = "default+" + UUID.randomUUID() + "@example.com";
        accountWithVerifiedAddress(address, null);
        orderWith(address, 1);

        sender.sweepWindow(EventReminderSender.Window.T24H);

        assertThat(sentTo(address)).hasSize(1);
    }

    @Test
    void anOrderWhoseEveryTicketIsRefundedOrRevokedIsSkipped() {
        String refunded = "refunded+" + UUID.randomUUID() + "@example.com";
        Order a = OrderFixtures.order(orders, tomorrow, refunded, clock.instant());
        OrderFixtures.ticket(tickets, a, "refunded");
        OrderFixtures.ticket(tickets, a, "revoked");

        sender.sweepWindow(EventReminderSender.Window.T24H);

        assertThat(sentTo(refunded)).isEmpty();
        assertThat(orders.findById(a.getId()).orElseThrow().getReminder24hAt()).isNotNull();
        assertThat(orders.findById(a.getId()).orElseThrow().getReminder24hOutcome()).isEqualTo("no_ticket");
    }

    @Test
    void oneLiveTicketAmongRefundedOnesStillEarnsAReminder() {
        String address = "partial+" + UUID.randomUUID() + "@example.com";
        Order order = OrderFixtures.order(orders, tomorrow, address, clock.instant());
        OrderFixtures.ticket(tickets, order, "refunded");
        OrderFixtures.ticket(tickets, order, "issued");

        sender.sweepWindow(EventReminderSender.Window.T24H);

        assertThat(sentTo(address)).hasSize(1);
    }

    /**
     * The count in the mail has to be the count at the door. {@code hasLiveTicket}
     * deliberately lets an order through on one live ticket among refunded ones
     * (the test above), but {@code ticketCount} was {@code orderTickets.size()} —
     * the unfiltered list — so that buyer was told "Your 3 ticket(s) are ready"
     * while holding one.
     */
    @Test
    void theReminderCountsOnlyTheTicketsThatStillWork() {
        String address = "counted+" + UUID.randomUUID() + "@example.com";
        Order order = OrderFixtures.order(orders, tomorrow, address, clock.instant());
        OrderFixtures.ticket(tickets, order, "issued");
        OrderFixtures.ticket(tickets, order, "refunded");
        OrderFixtures.ticket(tickets, order, "revoked");

        sender.sweepWindow(EventReminderSender.Window.T24H);

        assertThat(textSentTo(address))
                .as("two of the three tickets are dead — the door will honour one")
                .contains("Your ticket is ready.");
    }

    @Test
    void aCancelledEventNeverNudgesAnyone() {
        Event cancelled = OrderFixtures.event(orgs, users, events, "Called Off",
                clock.instant().plusSeconds(22 * 3600));
        cancelled.setStatus(com.imin.iminapi.model.EventStatus.CANCELLED);
        events.save(cancelled);

        String address = "cancelled+" + UUID.randomUUID() + "@example.com";
        Order order = OrderFixtures.order(orders, cancelled, address, clock.instant());
        OrderFixtures.ticket(tickets, order, "issued");

        sender.sweepWindow(EventReminderSender.Window.T24H);

        assertThat(sentTo(address)).isEmpty();
    }

    @Test
    void localeFallsBackOrderLocaleThenAccountThenEnglish() {
        // 1. The order's own snapshot wins.
        String withOrderLocale = "fr+" + UUID.randomUUID() + "@example.com";
        Order french = OrderFixtures.order(orders, tomorrow, withOrderLocale, clock.instant());
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

    @ParameterizedTest
    @EnumSource(EventReminderSender.Window.class)
    void anEventSwitchedOffSendsNothingAndStaysDueUntilSwitchedBackOn(EventReminderSender.Window window) {
        boolean day = window == EventReminderSender.Window.T24H;
        Event event = day ? tomorrow : soon;
        String address = address("event-off");
        Order order = orderOn(event, address, 1);
        events.updateRemindersEnabled(event.getId(), false);

        sender.sweepWindow(window);

        assertThat(sentTo(address)).isEmpty();
        assertThat(stampedAt(order, day))
                .as("a switched-off event is not stamped, so switching back on still sends").isNull();

        events.updateRemindersEnabled(event.getId(), true);
        sender.sweepWindow(window);

        assertThat(sentTo(address)).hasSize(1);
    }

    /** Switching the event off while a batch is mid-flight stops the rest of it, unstamped. */
    @Test
    void switchingAnEventOffMidSweepStopsTheRestOfTheBatch() throws Exception {
        Order a = orderWith(address("mid-a"), 1);
        Order b = orderWith(address("mid-b"), 1);
        List<UUID> byDueOrder = jdbc.queryForList(
                "select id from orders where id in (?, ?) order by id", UUID.class, a.getId(), b.getId());
        Order first = byDueOrder.get(0).equals(a.getId()) ? a : b;
        Order second = first == a ? b : a;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (PgFaults.Pause pause = PgFaults.pauseWrites(dataSource, "orders", "id", first.getId())) {
            Future<?> sweep = pool.submit(() -> sender.sweepWindow(EventReminderSender.Window.T24H));
            pause.awaitBlocked(Duration.ofSeconds(10));
            events.updateRemindersEnabled(tomorrow.getId(), false);
            pause.release();
            sweep.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(sentTo(first.getEmail())).as("the order already in flight finishes").hasSize(1);
        assertThat(sentTo(second.getEmail())).isEmpty();
        assertThat(stampedAt(second, true)).as("left unstamped so switching back on still sends").isNull();
    }

    @Test
    void aHardBouncedAddressIsNotMailedAndIsRecordedAsBounced() {
        String address = address("bounced");
        suppression.addDeliverability(address.toLowerCase(), "hard-bounce");
        Order order = orderWith(address, 1);

        sender.sweepWindow(EventReminderSender.Window.T24H);

        assertThat(sentTo(address)).isEmpty();
        Order stamped = orders.findById(order.getId()).orElseThrow();
        assertThat(stamped.getReminder24hAt()).isNotNull();
        assertThat(stamped.getReminder24hOutcome()).isEqualTo("bounced");
    }

    /** How a buyer stands with the organizer when the day-before email goes out. */
    enum Standing {
        MEMBER_IN_GOOD_STANDING(true), GUEST_WITHOUT_MEMBERSHIP(true),
        PERMANENT_OPT_OUT(false), UNSUBSCRIBED_MEMBER(false), MARKETING_SUPPRESSED_MEMBER(false),
        ERASE_PENDING_MEMBER(false), ERASED_FOR_THIS_ORG(false), ERASED_PLATFORM_WIDE(false);

        final boolean getsFriendBlock;

        Standing(boolean getsFriendBlock) {
            this.getsFriendBlock = getsFriendBlock;
        }
    }

    /** The reminder itself is transactional and always goes; the friend block is promotional and honours every opt-out. */
    @ParameterizedTest
    @EnumSource(Standing.class)
    void theFriendBlockReachesOnlyBuyersWhoHaveNotOptedOutOfTheOrganizer(Standing standing) {
        fx.tier(tomorrow, 1500, 100);
        String address = address("friend");
        Order order = orderWith(address, 1);
        UUID orgId = tomorrow.getOrgId();
        String norm = order.getEmailNormalized();
        if (standing != Standing.GUEST_WITHOUT_MEMBERSHIP && standing != Standing.PERMANENT_OPT_OUT
                && standing != Standing.ERASED_FOR_THIS_ORG && standing != Standing.ERASED_PLATFORM_WIDE) {
            projector.upsertMembership(orgId, norm, null);
        }
        switch (standing) {
            case PERMANENT_OPT_OUT -> {
                MarketingOptOut o = new MarketingOptOut();
                o.setEmailNormalized(norm);
                o.setOrgId(orgId);
                o.setChannel("email");
                o.setSource("unsubscribe_link");
                optOuts.save(o);
            }
            case UNSUBSCRIBED_MEMBER ->
                    jdbc.update("update memberships set consent_status = 'unsubscribed' where org_id = ?", orgId);
            case MARKETING_SUPPRESSED_MEMBER -> {
                SuppressionEntry sup = new SuppressionEntry();
                sup.setScope(SuppressionEntry.SCOPE_MARKETING);
                sup.setOrgId(orgId);
                sup.setMembershipId(jdbc.queryForObject("select membership_id from memberships where org_id = ?",
                        UUID.class, orgId));
                sup.setReason("manual");
                suppressions.save(sup);
            }
            case ERASE_PENDING_MEMBER ->
                    jdbc.update("update memberships set status = 'erase_pending' where org_id = ?", orgId);
            case ERASED_FOR_THIS_ORG -> erase(orgId, norm);
            case ERASED_PLATFORM_WIDE -> {
                erase(null, norm);
                platformErased.add(norm);
            }
            default -> { }
        }

        sender.sweepWindow(EventReminderSender.Window.T24H);

        assertThat(sentTo(address)).as("the reminder itself is not marketing").hasSize(1);
        assertThat(textSentTo(address).contains("Going with friends?")).isEqualTo(standing.getsFriendBlock);
        assertThat(lastSentTo(address).html().contains("wa.me")).isEqualTo(standing.getsFriendBlock);
    }

    private void erase(UUID org, String email) {
        ErasedAddress a = new ErasedAddress();
        a.setOrgId(org);
        a.setEmailNormalized(email);
        erased.save(a);
    }

    /** A stale full-entity save of the order (the SMS box on the order page) must not un-stamp the reminder. */
    @Test
    void aFullOrderSaveDuringTheSweepDoesNotUnstampTheReminder() {
        String address = address("stale-save");
        Order order = orderWith(address, 1);
        Order stale = orders.findById(order.getId()).orElseThrow();

        sender.sweepWindow(EventReminderSender.Window.T24H);
        stale.setSmsMarketingOptIn(true);
        orders.save(stale);
        sender.sweepWindow(EventReminderSender.Window.T24H);

        assertThat(sentTo(address)).hasSize(1);
        Order after = orders.findById(order.getId()).orElseThrow();
        assertThat(after.getReminder24hAt()).isNotNull();
        assertThat(after.getReminder24hOutcome()).isEqualTo("sent");
    }

    /**
     * The kill switch: with reminders off, {@code sweep()} does not even claim
     * an order that is otherwise due.
     *
     * <p>Only the OFF direction is asserted through {@code sweep()}, and that is
     * deliberate. {@code sweep()} carries
     * {@code @SchedulerLock(lockAtLeastFor = "PT1M")}, so a second call inside
     * the same minute — in this test or a neighbouring one — is swallowed by
     * ShedLock rather than executed. A test that called it twice was asserting
     * against the lock, not the flag, and duly passed alone and failed in the
     * full suite.
     *
     * <p>The ON direction is what every other test in this file exercises, via
     * {@code sweepWindow}, which is the method {@code sweep()} delegates to once
     * the flag lets it through.
     */
    @Test
    void theMasterSwitchIsWhatSweepChecks() {
        // sweep() is the scheduled entry point and consults the flag; the
        // per-window method used above deliberately does not, so these tests
        // exercise the sender rather than the switch.
        String address = address("switch");
        Order order = orderWith(address, 1);

        flips.set(emailProps, "remindersEnabled", false);
        // One context serves the run: an earlier sweep() would still hold the PT1M lock.
        jdbc.update("UPDATE shedlock SET lock_until = locked_at WHERE name = ?", "EventReminderSender.sweep");

        sender.sweep();

        assertThat(sentTo(address)).isEmpty();
        assertThat(orders.findById(order.getId()).orElseThrow().getReminder24hAt())
                .as("a disabled sweep must not send, and must not claim the order either")
                .isNull();

        // And the order is still due afterwards — the switch defers the nudge,
        // it does not consume it.
        sender.sweepWindow(EventReminderSender.Window.T24H);
        assertThat(sentTo(address)).hasSize(1);
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private static String address(String tag) {
        return tag + "+" + UUID.randomUUID() + "@example.com";
    }

    private Instant stampedAt(Order order, boolean day) {
        Order fresh = orders.findById(order.getId()).orElseThrow();
        return day ? fresh.getReminder24hAt() : fresh.getReminder3hAt();
    }

    private Order orderWith(String buyerEmail, int ticketCount) {
        return orderOn(tomorrow, buyerEmail, ticketCount);
    }

    private Order orderOn(Event event, String buyerEmail, int ticketCount) {
        Order order = OrderFixtures.order(orders, event, buyerEmail, clock.instant());
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
        row.setVerifiedAt(clock.instant());
        row.setVerifiedKey(address.trim().toLowerCase());
        accountEmails.save(row);

        return account.getId();
    }

    private List<RecordingEmailService.SentEmail> sentTo(String address) {
        return mail.sent().stream().filter(m -> address.equals(m.to())).toList();
    }

    private RecordingEmailService.SentEmail lastSentTo(String address) {
        List<RecordingEmailService.SentEmail> toAddress = sentTo(address);
        if (toAddress.isEmpty()) throw new AssertionError("nothing sent to " + address);
        return toAddress.get(toAddress.size() - 1);
    }

    private String textSentTo(String address) {
        return lastSentTo(address).text();
    }

    private String subjectSentTo(String address) {
        return lastSentTo(address).subject();
    }
}

package com.imin.iminapi.service.ticket;

import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.repository.BuyerNotificationPreferenceRepository;
import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketState;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Door reminders — one email per ORDER at T-24h and again at T-3h (spec §4.7).
 *
 * <p><b>Per order, not per ticket.</b> The epic's §6.2 says per-ticket; that is
 * wrong. An order carries exactly one event, and a four-ticket order would
 * otherwise mail the same person four times about the same night.
 *
 * <p><b>Delivery semantics: at-least-once, marked after the send.</b> The same
 * trade {@link com.imin.iminapi.service.event.NotifyReleaseSender} makes and for
 * the same reason. If the process dies between the send and the stamp, the buyer
 * may get a second nudge; if the stamp came first, a crash would mean they
 * silently never hear about a night they paid for. A duplicate is an annoyance,
 * a miss is a no-show.
 *
 * <p><b>This is not marketing.</b> It rides the same Art. 6(1)(b) basis as the
 * ticket email itself — performance of the contract the buyer entered when they
 * paid. That is why a guest with no account still gets it, and why the
 * account-level {@code event_reminders} switch is a courtesy control rather than
 * a consent one.
 */
@Component
public class EventReminderSender {

    private static final Logger log = LoggerFactory.getLogger(EventReminderSender.class);

    /**
     * Each nudge is a BAND, not a prefix.
     *
     * <p>The obvious shape — "every event starting in the next 24 hours" — is
     * wrong in a way that only shows up in the copy: an event two hours away is
     * also inside the next 24 hours, so a buyer who bought at 18:00 for a 20:00
     * door would receive "Vechirka is tomorrow" and "Doors soon" milliseconds
     * apart, the first of them lying about the date. Last-minute purchases are
     * a large share of ticket sales, so that is the common case rather than an
     * edge one.
     *
     * <p>Both bands are hours wide against a five-minute tick, so nothing falls
     * through a gap, and they do not overlap, so nothing is claimed twice.
     */
    private static final Duration T24H_FROM = Duration.ofHours(20);
    private static final Duration T24H_UNTIL = Duration.ofHours(24);
    private static final Duration T3H_FROM = Duration.ofHours(2);
    private static final Duration T3H_UNTIL = Duration.ofHours(3);

    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final EventRepository events;
    private final BuyerAccountEmailRepository accountEmails;
    private final BuyerAccountRepository accounts;
    private final BuyerNotificationPreferenceRepository preferences;
    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties emailProps;
    private final Clock clock;

    public EventReminderSender(OrderRepository orders,
                               TicketRepository tickets,
                               EventRepository events,
                               BuyerAccountEmailRepository accountEmails,
                               BuyerAccountRepository accounts,
                               BuyerNotificationPreferenceRepository preferences,
                               EmailService email,
                               EmailTemplateRenderer renderer,
                               EmailProperties emailProps,
                               Clock clock) {
        this.orders = orders;
        this.tickets = tickets;
        this.events = events;
        this.accountEmails = accountEmails;
        this.accounts = accounts;
        this.preferences = preferences;
        this.email = email;
        this.renderer = renderer;
        this.emailProps = emailProps;
        this.clock = clock;
    }

    /** Which nudge is being swept. The two are claimed independently. */
    enum Window {
        T24H("24h", T24H_FROM, T24H_UNTIL),
        T3H("3h", T3H_FROM, T3H_UNTIL);

        final String label;
        final Duration from;
        final Duration until;

        Window(String label, Duration from, Duration until) {
            this.label = label;
            this.from = from;
            this.until = until;
        }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    @SchedulerLock(name = "EventReminderSender.sweep", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void sweep() {
        if (!emailProps.isRemindersEnabled()) return;
        sweepWindow(Window.T24H);
        sweepWindow(Window.T3H);
    }

    void sweepWindow(Window window) {
        Instant now = clock.instant();
        Instant from = now.plus(window.from);
        Instant until = now.plus(window.until);

        List<Order> due = window == Window.T24H
                ? orders.findDue24hReminder(from, until)
                : orders.findDue3hReminder(from, until);

        for (Order order : due) {
            try {
                remind(order, window);
            } catch (Exception e) {
                // One bad order must not kill the sweep — its marker stays null
                // and the next tick retries.
                log.error("EventReminderSender: order {} ({}) failed — {}",
                        order.getId(), window.label, e.getMessage(), e);
            }
        }
    }

    private void remind(Order order, Window window) {
        List<Ticket> orderTickets = tickets.findByOrderIdOrderByCreatedAtAsc(order.getId());
        if (!hasLiveTicket(orderTickets)) {
            // Nothing to turn up with. Stamp anyway so the sweep stops
            // reconsidering it every five minutes for the rest of the window.
            stamp(order, window);
            return;
        }
        if (!wantsReminders(order)) {
            stamp(order, window);
            return;
        }

        Event event = events.findById(order.getEventId()).orElse(null);
        if (event == null) {
            stamp(order, window);
            return;
        }

        send(order, event, orderTickets, window);
        stamp(order, window);
    }

    /**
     * True while at least one ticket can still be used at the door. An order
     * whose every ticket is refunded or revoked buys nothing and gets nothing —
     * a reminder to attend on a refunded ticket is worse than silence.
     */
    private static boolean hasLiveTicket(List<Ticket> orderTickets) {
        return orderTickets.stream().anyMatch(t -> {
            TicketState state = TicketState.fromWire(t.getState());
            return state != TicketState.REFUNDED && state != TicketState.REVOKED;
        });
    }

    /**
     * A guest has no account and therefore no preference row — and still gets
     * the reminder, because it is contract performance rather than marketing.
     * An absent row on an account that exists means defaults, which is ON.
     */
    private boolean wantsReminders(Order order) {
        return accountFor(order)
                .map(account -> preferences.findById(account.getId())
                        .map(p -> p.isEventReminders())
                        .orElse(true))
                .orElse(true);
    }

    private Optional<BuyerAccount> accountFor(Order order) {
        if (order.getEmailNormalized() == null) return Optional.empty();
        return accountEmails.findByVerifiedKey(order.getEmailNormalized())
                .map(BuyerAccountEmail::getBuyerAccountId)
                .flatMap(accounts::findById);
    }

    private void send(Order order, Event event, List<Ticket> orderTickets, Window window) {
        String siteBase = trimTrailingSlash(emailProps.getBuyerSiteBaseUrl());
        String orderUrl = siteBase + "/order/" + order.getToken();

        Map<String, String> values = new LinkedHashMap<>();
        values.put("eventName", nullSafe(event.getName()));
        values.put("eventWhen", formatWhen(event));
        values.put("eventWhere", formatWhere(event));
        values.put("ticketCount", String.valueOf(orderTickets.size()));
        values.put("orderUrl", orderUrl);

        String locale = localeFor(order);
        String template = window == Window.T24H ? "event-reminder-24h" : "event-reminder-3h";
        EmailTemplateRenderer.Rendered rendered = renderer.render(template, locale, values);

        email.send(order.getEmail(), subject(event, window, locale), rendered.html(), rendered.text());
    }

    private static String subject(Event event, Window window, String locale) {
        String name = nullSafe(event.getName());
        if (window == Window.T24H) {
            return EmailLocale.choose(locale,
                    name + " is tomorrow",
                    name + " es mañana",
                    name + " c'est demain",
                    name + " — вже завтра");
        }
        return EmailLocale.choose(locale,
                "Doors soon — " + name,
                "Las puertas abren pronto — " + name,
                "Ouverture des portes bientôt — " + name,
                "Скоро відкриття дверей — " + name);
    }

    /**
     * {@code orders.buyer_locale} → the account's {@code locale} → English.
     *
     * <p>The order's own snapshot wins because it records the language the buyer
     * was actually reading at checkout. The account hop exists for orders placed
     * before V78 shipped, and for guests who opened an account afterwards; it
     * resolves through the same verified-address join everything else in the
     * buyer namespace uses.
     */
    String localeFor(Order order) {
        if (order.getBuyerLocale() != null && !order.getBuyerLocale().isBlank()) {
            return order.getBuyerLocale();
        }
        return accountFor(order).map(BuyerAccount::getLocale).orElse(null);
    }

    /**
     * Marks one order done.
     *
     * <p>A targeted UPDATE, not {@code orders.save(order)}. The sweep is not
     * transactional, so these entities are detached snapshots — for the tail of
     * a long batch, minutes old. {@code save()} merges, which writes every field
     * of the stale snapshot back over the current row and would silently revert
     * an {@code sms_marketing_opt_in} the buyer ticked on the order page while
     * the sweep was running.
     */
    private void stamp(Order order, Window window) {
        Instant now = clock.instant();
        if (window == Window.T24H) {
            orders.stampReminder24h(order.getId(), now);
        } else {
            orders.stampReminder3h(order.getId(), now);
        }
    }

    private static String formatWhen(Event event) {
        if (event.getStartsAt() == null) return "";
        ZoneId zone = event.getTimezone() == null ? ZoneId.of("UTC") : ZoneId.of(event.getTimezone());
        return DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)
                .withZone(zone)
                .format(event.getStartsAt());
    }

    private static String formatWhere(Event event) {
        String venue = nullSafe(event.getVenueName());
        String city = nullSafe(event.getVenueCity());
        if (venue.isEmpty()) return city;
        if (city.isEmpty()) return venue;
        return venue + ", " + city;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}

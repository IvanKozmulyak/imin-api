package com.imin.iminapi.service.event;

import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.model.BuyerNotificationPreference;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerNotificationPreferenceRepository;
import com.imin.iminapi.buyer.repository.BuyerPushDeviceRepository;
import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.push.ExpoPushSender;
import com.imin.iminapi.push.PushMessage;
import com.imin.iminapi.push.PushProperties;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Makes good on the "We'll email you if tickets release" promise the public event page
 * shows when an event has nothing purchasable.
 *
 * <p>Every tick this sweeps the {@code notify_subscriptions} rows that have not been
 * notified, keeps the events that are publicly listable AND have at least one tier that
 * is purchasable right now ({@link TierAvailability#isPurchasable} — the same predicate
 * that decides whether the notify-me form is shown at all), and mails the pending
 * subscribers exactly once.
 *
 * <p><b>Delivery semantics: at-least-once.</b> The send happens before the row is marked,
 * so a crash in that window means the address gets a duplicate on the next tick. That is
 * the deliberate trade: a duplicate one-shot informational email is a far smaller failure
 * than silently never sending it. A send that throws leaves the row pending and is retried
 * on the next tick.
 *
 * <p>Consent: this is a transactional one-shot the buyer explicitly asked for at the point
 * of collection, not marketing — no {@code ConsentService}/{@code SendGateService} gate
 * (both are membership-keyed and a notify-me subscriber may have never bought anything).
 * Deliverability suppression IS honoured: a suppressed address is marked as notified with
 * no send, so we neither mail it nor re-scan it forever.
 *
 * <p><b>Push rides along, and never instead.</b> Signed-in buyers with a registered
 * device also get a drop-alert push ({@link #pushToAccountHolders}). It runs after the
 * email loop, outside its try/catch, and never touches {@code mark(sub)} — a push
 * outage must not suppress an email somebody is owed, nor cause a second one. Guests
 * keep getting email only, because a device belongs to an account and
 * {@code notify_subscriptions} does not.
 *
 * <p>Follows the {@link ReservationSweeper} scheduling pattern — 60s fixed delay, 30s
 * initial delay, ShedLock so only one replica sweeps per cycle.
 */
@Component
public class NotifyReleaseSender {

    private static final Logger log = LoggerFactory.getLogger(NotifyReleaseSender.class);

    private final NotifySubscriptionRepository subscriptions;
    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final SuppressionRepository suppressions;
    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties emailProps;
    private final Clock clock;
    private final PushProperties pushProps;
    private final ExpoPushSender push;
    private final BuyerPushDeviceRepository pushDevices;
    private final BuyerAccountEmailRepository buyerEmails;
    private final BuyerNotificationPreferenceRepository pushPrefs;

    public NotifyReleaseSender(NotifySubscriptionRepository subscriptions,
                               EventRepository events,
                               TicketTierRepository tiers,
                               SuppressionRepository suppressions,
                               EmailService email,
                               EmailTemplateRenderer renderer,
                               EmailProperties emailProps,
                               Clock clock,
                               PushProperties pushProps,
                               ExpoPushSender push,
                               BuyerPushDeviceRepository pushDevices,
                               BuyerAccountEmailRepository buyerEmails,
                               BuyerNotificationPreferenceRepository pushPrefs) {
        this.subscriptions = subscriptions;
        this.events = events;
        this.tiers = tiers;
        this.suppressions = suppressions;
        this.email = email;
        this.renderer = renderer;
        this.emailProps = emailProps;
        this.clock = clock;
        this.pushProps = pushProps;
        this.push = push;
        this.pushDevices = pushDevices;
        this.buyerEmails = buyerEmails;
        this.pushPrefs = pushPrefs;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @SchedulerLock(name = "NotifyReleaseSender.sweep", lockAtLeastFor = "PT10S", lockAtMostFor = "PT5M")
    public void sweep() {
        List<UUID> eventIds = subscriptions.findEventIdsWithPendingSubscriptions();
        if (eventIds.isEmpty()) return;

        Instant now = clock.instant();
        for (UUID eventId : eventIds) {
            try {
                Event event = events.findById(eventId).orElse(null);
                if (event == null || !isReleased(event, now)) continue;
                notifySubscribers(event);
            } catch (Exception e) {
                // One bad event must not kill the sweep — its rows stay pending and
                // the next tick retries.
                log.error("NotifyReleaseSender: event {} failed — {}", eventId, e.getMessage(), e);
            }
        }
    }

    /**
     * True when the event is publicly listable (not deleted, PUBLIC, published, not DRAFT,
     * not CANCELLED, not PAST) and at least one of its enabled tiers is purchasable now —
     * i.e. exactly the state that flips the buyer page from "notify me" to "buy".
     */
    private boolean isReleased(Event event, Instant now) {
        if (event.getDeletedAt() != null) return false;
        if (event.getVisibility() != EventVisibility.PUBLIC) return false;
        if (event.getPublishedAt() == null) return false;
        EventStatus status = event.getStatus();
        if (status == EventStatus.DRAFT || status == EventStatus.CANCELLED || status == EventStatus.PAST) {
            return false;
        }
        return tiers.findByEventIdAndEnabledTrueOrderBySortOrderAsc(event.getId()).stream()
                .anyMatch(t -> TierAvailability.isPurchasable(event, t, now));
    }

    private void notifySubscribers(Event event) {
        List<NotifySubscription> pending = subscriptions.findPendingByEventId(event.getId());
        if (pending.isEmpty()) return;

        Set<String> suppressed = new HashSet<>(suppressions.findDeliverabilityEmailsIn(
                pending.stream().map(s -> normalize(s.getEmail())).toList()));

        // The event body is identical for every subscriber, but the language is not (V77
        // stores the locale the buyer signed up in). Render once PER LOCALE and reuse —
        // a sold-out headliner can have thousands of pending rows and at most four bodies.
        Map<String, EmailTemplateRenderer.Rendered> byLocale = new HashMap<>();

        int sent = 0;
        int skipped = 0;
        for (NotifySubscription sub : pending) {
            try {
                if (suppressed.contains(normalize(sub.getEmail()))) {
                    // Deliverability-suppressed: mark so we stop re-scanning it, never send.
                    mark(sub);
                    skipped++;
                    log.info("NotifyReleaseSender: suppressed address on event {} — marked, not sent",
                            event.getId());
                    continue;
                }
                String locale = EmailLocale.normalize(sub.getLocale());
                EmailTemplateRenderer.Rendered rendered =
                        byLocale.computeIfAbsent(locale, l -> render(event, l));
                String subject = subject(event, locale);
                email.send(sub.getEmail(), subject, rendered.html(), rendered.text());
                mark(sub);
                sent++;
            } catch (Exception e) {
                // Row stays pending → retried next tick (at-least-once).
                log.warn("NotifyReleaseSender: send failed for subscription {} on event {} — {}",
                        sub.getId(), event.getId(), e.getMessage());
            }
        }
        log.info("NotifyReleaseSender: event {} sent={} suppressed={} pending={}",
                event.getId(), sent, skipped, pending.size() - sent - skipped);

        pushToAccountHolders(event, pending);
    }

    /**
     * Best-effort push alongside the drop-alert email.
     *
     * <p><b>Deliberately after the email loop and outside its try/catch.</b>
     * {@link #mark(NotifySubscription)} is the one-shot delivery marker and it is
     * driven by the email alone; letting a push failure influence it would either
     * re-send a mail somebody already has or suppress one they are owed. Push is
     * the enhancement, email is the promise.
     *
     * <p>Only account holders are reachable: {@code notify_subscriptions} is
     * keyed by email and works for guests, while a device belongs to a signed-in
     * buyer. The join goes through <b>verified</b> addresses only — the same
     * boundary {@code GET /buyer/orders} uses, because an unverified row is a
     * claim anybody can make about any address.
     *
     * <p>No {@code @Transactional} here, and it must not gain one: the method is
     * private and reached by self-invocation, which proxy-based AOP never
     * intercepts, so the annotation would be inert while looking load-bearing.
     * The revoke's boundary lives on
     * {@code BuyerPushDeviceRepository.revokeByTokens} instead.
     */
    private void pushToAccountHolders(Event event, List<NotifySubscription> pending) {
        if (!pushProps.isEnabled()) return;
        try {
            List<UUID> accountIds = pending.stream()
                    .map(s -> normalize(s.getEmail()))
                    .distinct()
                    .map(buyerEmails::findByVerifiedKey)
                    .flatMap(Optional::stream)
                    .map(BuyerAccountEmail::getBuyerAccountId)
                    .distinct()
                    .filter(this::pushOptedIn)
                    .toList();
            if (accountIds.isEmpty()) return;

            List<String> tokens = pushDevices.findLiveTokensForAccounts(accountIds);
            if (tokens.isEmpty()) return;

            String title = "Tickets are live";
            String body = eventName(event);
            List<PushMessage> messages = tokens.stream()
                    .map(t -> new PushMessage(t, title, body, PushMessage.CHANNEL_DROP_ALERTS,
                            Map.of("type", "drop-alert", "eventId", event.getId().toString())))
                    .toList();

            ExpoPushSender.Result result = push.send(messages);
            if (!result.deadTokens().isEmpty()) {
                // Uninstalled apps never tell us they are gone; this is the only
                // signal, so acting on it is what keeps the registry from rotting.
                pushDevices.revokeByTokens(result.deadTokens(), clock.instant());
            }
        } catch (Exception e) {
            log.warn("NotifyReleaseSender: push fan-out failed for event {} — {}",
                    event.getId(), e.getMessage());
        }
    }

    /** Absent preference row means defaults, and the default is on. */
    private boolean pushOptedIn(UUID accountId) {
        return pushPrefs.findById(accountId)
                .map(BuyerNotificationPreference::isPushDropAlerts)
                .orElse(true);
    }

    private void mark(NotifySubscription sub) {
        sub.setNotifiedAt(clock.instant());
        subscriptions.save(sub);
    }

    private EmailTemplateRenderer.Rendered render(Event event, String locale) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("eventName", eventName(event));
        values.put("eventWhen", formatWhen(event));
        values.put("eventWhere", formatWhere(event));
        values.put("eventUrl", buyerSiteBase() + "/e/" + event.getId());
        return renderer.render("notify-release", locale, values);
    }

    private static String subject(Event event, String locale) {
        String name = eventName(event);
        return EmailLocale.choose(locale,
                "Tickets are available for " + name,
                "Ya hay entradas disponibles para " + name,
                "Des billets sont disponibles pour " + name,
                "Квитки на " + name + " уже доступні");
    }

    private String buyerSiteBase() {
        String s = emailProps.getBuyerSiteBaseUrl();
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String eventName(Event event) {
        String name = event.getName();
        return name == null || name.isBlank() ? "this event" : name;
    }

    /** Date/time in the EVENT's timezone — same convention as {@code TicketIssuanceEmailer}. */
    private static String formatWhen(Event event) {
        if (event.getStartsAt() == null) return "Date to be announced";
        ZoneId zone = event.getTimezone() == null
                ? ZoneId.systemDefault()
                : ZoneId.of(event.getTimezone());
        return DateTimeFormatter.ofPattern("EEEE, d LLL yyyy · HH:mm")
                .withZone(zone)
                .format(event.getStartsAt());
    }

    private static String formatWhere(Event event) {
        String name = event.getVenueName();
        String city = event.getVenueCity();
        boolean hasName = name != null && !name.isBlank();
        boolean hasCity = city != null && !city.isBlank();
        if (hasName && hasCity) return name + ", " + city;
        if (hasName) return name;
        if (hasCity) return city;
        return "Venue to be announced";
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}

package com.imin.iminapi.service.event;

import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.NotifySubscription;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    public NotifyReleaseSender(NotifySubscriptionRepository subscriptions,
                               EventRepository events,
                               TicketTierRepository tiers,
                               SuppressionRepository suppressions,
                               EmailService email,
                               EmailTemplateRenderer renderer,
                               EmailProperties emailProps,
                               Clock clock) {
        this.subscriptions = subscriptions;
        this.events = events;
        this.tiers = tiers;
        this.suppressions = suppressions;
        this.email = email;
        this.renderer = renderer;
        this.emailProps = emailProps;
        this.clock = clock;
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

        EmailTemplateRenderer.Rendered rendered = render(event);
        String subject = "Tickets are available for " + eventName(event);

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
    }

    private void mark(NotifySubscription sub) {
        sub.setNotifiedAt(clock.instant());
        subscriptions.save(sub);
    }

    private EmailTemplateRenderer.Rendered render(Event event) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("eventName", eventName(event));
        values.put("eventWhen", formatWhen(event));
        values.put("eventWhere", formatWhere(event));
        values.put("eventUrl", buyerSiteBase() + "/e/" + event.getId());
        return renderer.render("notify-release", values);
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

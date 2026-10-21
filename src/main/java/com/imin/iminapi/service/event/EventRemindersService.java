package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.EventRemindersRequest;
import com.imin.iminapi.dto.event.EventRemindersResponse;
import com.imin.iminapi.dto.event.ReminderPreviewResponse;
import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.marketing.render.OrganizerIdentity;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RoleGuard;
import com.imin.iminapi.service.analytics.NetOrderRevenue;
import com.imin.iminapi.service.ticket.AppleWalletPassService;
import com.imin.iminapi.service.ticket.EventReminderSender;
import com.imin.iminapi.service.ticket.ReminderEmailComposer;
import com.imin.iminapi.service.ticket.ReminderShareLink;
import com.imin.iminapi.service.ticket.google.GoogleWalletPassService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Organizer view and controls of the door reminders for one event, plus the exact-email preview. */
@Service
public class EventRemindersService {

    public static final int DOOR_NOTE_MAX = 280;

    private final EventRepository events;
    private final OrderRepository orders;
    private final FunnelEventRepository funnel;
    private final OrganizationRepository orgs;
    private final TicketTierRepository tiers;
    private final EmailProperties emailProps;
    private final ReminderEmailComposer composer;
    private final AppleWalletPassService apple;
    private final GoogleWalletPassService google;
    private final Clock clock;

    public EventRemindersService(EventRepository events, OrderRepository orders, FunnelEventRepository funnel,
                                 OrganizationRepository orgs, TicketTierRepository tiers, EmailProperties emailProps,
                                 ReminderEmailComposer composer, AppleWalletPassService apple,
                                 GoogleWalletPassService google, Clock clock) {
        this.events = events;
        this.orders = orders;
        this.funnel = funnel;
        this.orgs = orgs;
        this.tiers = tiers;
        this.emailProps = emailProps;
        this.composer = composer;
        this.apple = apple;
        this.google = google;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public EventRemindersResponse settings(AuthPrincipal principal, UUID eventId) {
        return toResponse(loadOwned(principal, eventId));
    }

    /** Omitted fields stay; an empty note clears it. Owners and admins only. */
    @Transactional
    public EventRemindersResponse update(AuthPrincipal principal, UUID eventId, EventRemindersRequest req) {
        RoleGuard.requireAtLeast(principal, UserRole.ADMIN, "change reminder settings");
        Event e = loadOwned(principal, eventId);
        if (req == null) return toResponse(e);
        // Validate first so a refused note leaves the switches in the same request untouched.
        String note = req.doorNote() == null ? null : validNote(req.doorNote());
        if (req.enabled() != null) {
            events.updateRemindersEnabled(e.getId(), req.enabled());
            e.setRemindersEnabled(req.enabled());
        }
        if (req.shareLinkEnabled() != null) {
            events.updateReminderShareEnabled(e.getId(), req.shareLinkEnabled());
            e.setReminderShareEnabled(req.shareLinkEnabled());
        }
        if (req.doorNote() != null) {
            events.updateReminderDoorNote(e.getId(), note);
            e.setReminderDoorNote(note);
        }
        return toResponse(e);
    }

    @Transactional(readOnly = true)
    public ReminderPreviewResponse preview(AuthPrincipal principal, UUID eventId, String windowParam, String locale) {
        Event e = loadOwned(principal, eventId);
        EventReminderSender.Window window = switch (windowParam == null ? "" : windowParam) {
            case "day_before" -> EventReminderSender.Window.T24H;
            case "doors_soon" -> EventReminderSender.Window.T3H;
            default -> throw fieldInvalid("window", "must be day_before or doors_soon");
        };
        String norm = EmailLocale.normalize(locale);
        Organization org = orgs.findById(e.getOrgId()).orElse(null);

        // Example tickets: this event's own tier names, a placeholder token, no real order.
        List<TicketTier> eventTiers = tiers.findByEventIdOrderBySortOrderAsc(e.getId());
        List<Ticket> examples = eventTiers.stream().limit(2).map(EventRemindersService::example).toList();
        if (examples.isEmpty()) examples = List.of(example(null));

        String base = emailProps.getBuyerSiteBaseUrl() == null ? "" : emailProps.getBuyerSiteBaseUrl().replaceAll("/+$", "");
        ReminderEmailComposer.Composed mail = composer.compose(e, org, examples, base + "/order/preview", window, norm,
                composer.friendBlockEligible(e, window));
        String organizer = OrganizerIdentity.singleLine(org == null ? null : org.displayName());
        return new ReminderPreviewResponse(mail.subject(), organizer.isEmpty() ? "IMIN" : organizer + " via IMIN",
                mail.html(), mail.text());
    }

    private static Ticket example(TicketTier tier) {
        Ticket t = new Ticket();
        t.setToken("preview");
        t.setState("issued");
        t.setTierName(tier == null ? "General" : tier.getName());
        return t;
    }

    private EventRemindersResponse toResponse(Event e) {
        Instant now = clock.instant();
        EventRemindersResponse.Window day = window(e, EventReminderSender.Window.T24H,
                orders.reminderOutcomeCounts24h(e.getId()), now);
        EventRemindersResponse.Window doors = window(e, EventReminderSender.Window.T3H,
                orders.reminderOutcomeCounts3h(e.getId()), now);

        String source = ReminderShareLink.UTM_SOURCE;
        String campaign = ReminderShareLink.UTM_CAMPAIGN;
        List<Object[]> rows = orders.revenueRowsByEventAndUtm(e.getId(), source, campaign);
        long keptOrders = NetOrderRevenue.keptOrdersByKey(rows).values().stream().mapToLong(Long::longValue).sum();
        long revenue = NetOrderRevenue.sumByKey(rows).values().stream().mapToLong(Long::longValue).sum();
        EventRemindersResponse.Share share = new EventRemindersResponse.Share(
                e.getVisibility() == EventVisibility.PUBLIC,
                composer.friendBlockEligible(e, EventReminderSender.Window.T24H),
                funnel.countDistinctAnonForEventAndUtm(e.getId(), source, campaign),
                keptOrders, revenue, e.getCurrency());

        return new EventRemindersResponse(e.isRemindersEnabled(), e.isReminderShareEnabled(), e.getReminderDoorNote(),
                emailProps.isRemindersEnabled(), orders.countOrdersWithLiveTicket(e.getId()), day, doors, share,
                new EventRemindersResponse.Wallet(apple.isConfigured(), google.isConfigured()));
    }

    private static EventRemindersResponse.Window window(Event e, EventReminderSender.Window w,
                                                        List<Object[]> counts, Instant now) {
        long sent = 0, optedOut = 0, bounced = 0;
        Instant firstSent = null;
        for (Object[] r : counts) {
            String outcome = (String) r[0];
            long n = ((Number) r[1]).longValue();
            switch (outcome) {
                case "sent" -> {
                    sent += n;
                    firstSent = (Instant) r[2];
                }
                case "opted_out" -> optedOut += n;
                case "bounced" -> bounced += n;
                default -> { }
            }
        }
        Instant start = e.getStartsAt();
        if (start == null) return new EventRemindersResponse.Window(null, "no_date", sent, optedOut, bounced, firstSent);
        Instant opens = start.minus(w.until);
        String state;
        if (sent > 0) state = "sent";
        else if (e.getStatus() == EventStatus.CANCELLED) state = "cancelled";
        else if (!now.isBefore(start.minus(w.from))) state = "passed";
        else if (!now.isBefore(opens)) state = "sending";
        else state = "upcoming";
        // A cancelled event is never mailed, so it carries no send time.
        return new EventRemindersResponse.Window("cancelled".equals(state) ? null : opens, state, sent, optedOut, bounced,
                firstSent);
    }

    /** Trimmed; empty becomes null; no link-like text and at most 280 characters. */
    static String validNote(String raw) {
        String note = raw.strip();
        if (note.isEmpty()) return null;
        String lower = note.toLowerCase(Locale.ROOT);
        if (note.length() > DOOR_NOTE_MAX) throw fieldInvalid("doorNote", "must be at most 280 characters");
        if (lower.contains("://") || lower.contains("www.")) throw fieldInvalid("doorNote", "must not contain links");
        return note;
    }

    private Event loadOwned(AuthPrincipal principal, UUID eventId) {
        Event e = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(principal.orgId())) throw ApiException.notFound("Event");
        return e;
    }

    private static ApiException fieldInvalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Validation failed",
                Map.of(field, message));
    }
}

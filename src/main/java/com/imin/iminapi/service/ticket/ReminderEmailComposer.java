package com.imin.iminapi.service.ticket;

import com.imin.iminapi.audience.service.MarketingOptedOutCheck;
import com.imin.iminapi.dto.publicapi.TicketWallets;
import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.ModerationHoldRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.service.event.TierAvailability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One renderer for the door reminders, used by the sweep and by the organizer's preview so
 * the preview is the email. Everything buyer-facing in the blocks (date, count, labels) is
 * localized here; the organizer's door note is escaped and shown as written.
 */
@Component
public class ReminderEmailComposer {

    private static final Logger log = LoggerFactory.getLogger(ReminderEmailComposer.class);

    private static final String EXTRAS_MARKER = "__REMINDER_EXTRAS__";
    private static final String NBSP = " ";

    public record Composed(String subject, String html, String text) {}

    private final EmailTemplateRenderer renderer;
    private final WalletOffers wallet;
    private final ReminderShareLink share;
    private final TicketTierRepository tiers;
    private final ModerationHoldRepository holds;
    private final MarketingOptedOutCheck optedOut;
    private final Clock clock;

    public ReminderEmailComposer(EmailTemplateRenderer renderer, WalletOffers wallet, ReminderShareLink share,
                                 TicketTierRepository tiers, ModerationHoldRepository holds,
                                 MarketingOptedOutCheck optedOut, Clock clock) {
        this.renderer = renderer;
        this.wallet = wallet;
        this.share = share;
        this.tiers = tiers;
        this.holds = holds;
        this.optedOut = optedOut;
        this.clock = clock;
    }

    /**
     * Whether the day-before email may carry the friend block, before looking at any buyer:
     * the window, the organizer's switch, a public page, sales not suspended, a purchasable tier.
     * The organizer's card and preview use this; the sweep adds the per-buyer opt-out.
     */
    public boolean friendBlockEligible(Event event, EventReminderSender.Window window) {
        if (window != EventReminderSender.Window.T24H) return false;
        if (!event.isReminderShareEnabled()) return false;
        if (event.getVisibility() != EventVisibility.PUBLIC) return false;
        if (holds.salesSuspended(event.getOrgId(), event.getId())) return false;
        var now = clock.instant();
        return tiers.findByEventIdOrderBySortOrderAsc(event.getId()).stream()
                .anyMatch(t -> TierAvailability.isPurchasable(event, t, now));
    }

    /**
     * {@link #friendBlockEligible(Event, EventReminderSender.Window)} for one real buyer: false for
     * anyone who opted out of this organizer in any way, and for an order with no address to check.
     */
    public boolean friendBlockEligible(Event event, EventReminderSender.Window window, String buyerEmailNormalized) {
        if (buyerEmailNormalized == null) return false;
        return friendBlockEligible(event, window) && !optedOut.isOptedOut(event.getOrgId(), buyerEmailNormalized);
    }

    public Composed compose(Event event, Organization org, List<Ticket> liveTickets, String orderUrl,
                            EventReminderSender.Window window, String locale, boolean friendEligible) {
        String norm = EmailLocale.normalize(locale);
        Copy c = Copy.of(norm);
        boolean dayBefore = window == EventReminderSender.Window.T24H;

        Map<String, String> values = new LinkedHashMap<>();
        values.put("eventName", nullSafe(event.getName()));
        values.put("eventWhen", formatWhen(event, norm));
        values.put("eventWhere", formatWhere(event));
        values.put("orderUrl", orderUrl);
        values.put("extras", EXTRAS_MARKER);
        if (dayBefore) values.put("ticketsReady", c.ticketsReady(liveTickets.size()));

        String template = dayBefore ? "event-reminder-24h" : "event-reminder-3h";
        EmailTemplateRenderer.Rendered r = renderer.render(template, norm, values);

        StringBuilder html = new StringBuilder();
        StringBuilder text = new StringBuilder();
        directions(event, c, html, text);
        doorNote(event, org, c, html, text);
        walletRows(liveTickets, c, html, text);
        if (dayBefore && friendEligible) friendBlock(event, c, html, text);

        return new Composed(subject(event, dayBefore, norm),
                r.html().replace(EXTRAS_MARKER, html.toString()),
                r.text().replace(EXTRAS_MARKER, text.toString()));
    }

    // ── blocks ──────────────────────────────────────────────────────────

    private void directions(Event event, Copy c, StringBuilder html, StringBuilder text) {
        String url = directionsUrl(event);
        if (url == null) return;
        html.append(row(link(url, c.directions + " &rarr;")));
        text.append("\n\n").append(c.directions).append(colon(c)).append(url);
    }

    /** French sets a no-break space before a colon; the other languages do not. */
    private static String colon(Copy c) {
        return "fr".equals(c.lang()) ? NBSP + ": " : ": ";
    }

    private void doorNote(Event event, Organization org, Copy c, StringBuilder html, StringBuilder text) {
        String note = event.getReminderDoorNote();
        if (note == null || note.isBlank()) return;
        String from = c.fromOrganizer(org == null ? "" : org.displayName());
        html.append(row("<div style=\"font-size:12px; color:#7a738f; line-height:1.6;\">" + escape(from) + "</div>"
                + "<div style=\"margin-top:4px; font-size:14px; color:#11091f; line-height:1.6;\">"
                + escape(note).replace("\n", "<br>") + "</div>"));
        text.append("\n\n").append(from).append('\n').append(note);
    }

    private void walletRows(List<Ticket> liveTickets, Copy c, StringBuilder html, StringBuilder text) {
        int total = liveTickets.size();
        for (int i = 0; i < total; i++) {
            Ticket t = liveTickets.get(i);
            TicketWallets w = wallet.forTicket(t);
            boolean apple = w.apple().available();
            boolean google = w.google().available();
            if (!apple && !google) continue;
            String head = c.ticketOf(i + 1, total);
            StringBuilder h = new StringBuilder("<div style=\"font-size:12px; color:#7a738f; line-height:1.6;\">"
                    + escape(head) + " &middot; " + escape(nullSafe(t.getTierName())) + "</div>");
            text.append("\n\n").append(head).append(" — ").append(nullSafe(t.getTierName()));
            if (apple) {
                h.append(button(w.apple().url(), c.addApple));
                text.append("\n  ").append(c.addApple).append(colon(c)).append(w.apple().url());
            }
            if (google) {
                h.append(button(w.google().url(), c.addGoogle));
                text.append("\n  ").append(c.addGoogle).append(colon(c)).append(w.google().url());
            }
            html.append(row(h.toString()));
        }
    }

    private void friendBlock(Event event, Copy c, StringBuilder html, StringBuilder text) {
        ReminderShareLink.Links links = share.forEvent(event,
                c.inviteLine(nullSafe(event.getName())), nullSafe(event.getName()));
        html.append(row("<div style=\"font-size:14px; font-weight:600; color:#11091f;\">" + escape(c.friendsTitle) + "</div>"
                + button(links.whatsapp(), "WhatsApp") + button(links.telegram(), "Telegram")
                + button(links.mailto(), c.mailLabel)));
        text.append("\n\n").append(c.friendsTitle)
                .append("\n  WhatsApp: ").append(links.whatsapp())
                .append("\n  Telegram: ").append(links.telegram())
                .append("\n  ").append(c.mailLabel).append(colon(c)).append(links.mailto());
    }

    private static String row(String inner) {
        return "<tr><td style=\"padding:18px 32px 0;\">" + inner + "</td></tr>";
    }

    private static String link(String url, String labelHtml) {
        return "<a href=\"" + escape(url) + "\" style=\"font-size:13px; font-weight:600; color:#2d5cff; text-decoration:none;\">"
                + labelHtml + "</a>";
    }

    private static String button(String url, String label) {
        return "<div style=\"margin-top:6px;\">" + link(url, escape(label) + " &rarr;") + "</div>";
    }

    // ── facts ───────────────────────────────────────────────────────────

    /** Subject, localized; the sweep and the preview share it. */
    public String subject(Event event, boolean dayBefore, String locale) {
        String name = nullSafe(event.getName());
        if (dayBefore) {
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
     * The door time in the event's own zone and the buyer's language. The zone falls back to UTC
     * rather than throwing: a malformed organizer-supplied zone must not stop the reminder.
     */
    static String formatWhen(Event event, String locale) {
        if (event.getStartsAt() == null) return "";
        ZoneId zone = ZoneOffset.UTC;
        if (event.getTimezone() != null) {
            try {
                zone = ZoneId.of(event.getTimezone());
            } catch (RuntimeException e) {
                log.warn("Event {} has an unusable timezone {} — formatting the reminder in UTC",
                        event.getId(), event.getTimezone());
            }
        }
        String norm = EmailLocale.normalize(locale);
        String pattern = "uk".equals(norm) ? "EEE, d MMM · HH:mm" : "EEE d MMM · HH:mm";
        return DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag(norm))
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

    /** Same formula as the public event page: the venue point when geocoded, else the typed address. */
    static String directionsUrl(Event event) {
        Double lat = event.getVenueLatitude();
        Double lng = event.getVenueLongitude();
        if (lat != null && lng != null) {
            return "https://www.google.com/maps/search/?api=1&query=" + lat + "," + lng;
        }
        String query = Stream.of(event.getVenueName(), event.getVenueStreet(), event.getVenuePostalCode(),
                        event.getVenueCity(), event.getVenueCountry())
                .filter(p -> p != null && !p.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(", "));
        if (query.isEmpty()) return null;
        return "https://www.google.com/maps/search/?api=1&query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String escape(String input) {
        StringBuilder out = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    // ── copy ────────────────────────────────────────────────────────────

    /** Ukrainian noun form for a count: 1, 21 → one; 2-4, 22-24 → few; the rest → many. */
    static String ukTickets(int n) {
        int mod10 = n % 10;
        int mod100 = n % 100;
        if (mod10 == 1 && mod100 != 11) return "квиток";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "квитки";
        return "квитків";
    }

    private record Copy(String lang, String directions, String friendsTitle, String mailLabel,
                        String addApple, String addGoogle) {

        static Copy of(String lang) {
            return switch (lang) {
                case "es" -> new Copy(lang, "Cómo llegar", "¿Vas con amigos?", "Email",
                        "Añadir a Apple Wallet", "Guardar en Google Wallet");
                case "fr" -> new Copy(lang, "Itinéraire", "Tu viens avec des amis" + NBSP + "?", "E-mail",
                        "Ajouter à Apple Wallet", "Enregistrer dans Google Wallet");
                case "uk" -> new Copy(lang, "Як дістатися", "Ідеш із друзями?", "Пошта",
                        "Додати до Apple Wallet", "Зберегти в Google Wallet");
                default -> new Copy("en", "Get directions", "Going with friends?", "Email",
                        "Add to Apple Wallet", "Save to Google Wallet");
            };
        }

        String ticketsReady(int n) {
            return switch (lang) {
                case "es" -> n == 1 ? "Tu entrada está lista." : "Tus " + n + " entradas están listas.";
                case "fr" -> n == 1 ? "Ton billet est prêt." : "Tes " + n + " billets sont prêts.";
                case "uk" -> n == 1 ? "Твій квиток готовий." : "Твої квитки готові: " + n + " " + ukTickets(n) + ".";
                default -> n == 1 ? "Your ticket is ready." : "Your " + n + " tickets are ready.";
            };
        }

        String fromOrganizer(String org) {
            return switch (lang) {
                case "es" -> "De parte de " + org;
                case "fr" -> "De la part de " + org + NBSP + ":";
                case "uk" -> "Від " + org;
                default -> "From " + org;
            };
        }

        String ticketOf(int i, int total) {
            return switch (lang) {
                case "es" -> "Entrada " + i + " de " + total;
                case "fr" -> "Billet " + i + " sur " + total;
                case "uk" -> "Квиток " + i + " з " + total;
                default -> "Ticket " + i + " of " + total;
            };
        }

        String inviteLine(String eventName) {
            return switch (lang) {
                case "es" -> "Ven conmigo a " + eventName;
                case "fr" -> "Viens avec moi à " + eventName;
                case "uk" -> "Приєднуйся до мене: " + eventName;
                default -> "Come with me to " + eventName;
            };
        }
    }
}

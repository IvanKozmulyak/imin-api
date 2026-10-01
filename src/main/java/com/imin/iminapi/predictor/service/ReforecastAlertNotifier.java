package com.imin.iminapi.predictor.service;

import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Notification;
import com.imin.iminapi.model.NotificationPreferences;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.predictor.dto.ReforecastResult;
import com.imin.iminapi.predictor.model.ProjectionBand;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotificationPreferencesRepository;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Predictor alerts to the organizer who created the event: trajectory band crossings (in-app only, English) and
 * radar date-check changes (in-app in the creator's locale plus the {@code predictor-radar-alert} email to the
 * org contact address; in-app only when it is blank).
 *
 * <p><b>One predictor alert per event per day.</b> Both kinds first check the creator's {@code predictorShift}
 * preference (no row = on), then claim the event's local day in {@code predictor_alert}; the first claim of the day
 * wins across both kinds, and a lost claim sends nothing. A band crossing is otherwise emitted only when
 * {@code ReforecastService} sees the band change, so repeated recomputes inside one band emit nothing.
 *
 * <p>Copy is honest: the band alert states the old and new band and the projected range, never "will"; the radar
 * alert states the verdict change and both risk scores, never that the risk went up.
 */
@Component
public class ReforecastAlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(ReforecastAlertNotifier.class);

    static final String RADAR_KIND = "predictor.radar.worsened";
    static final String RADAR_TEMPLATE = "predictor-radar-alert";
    /** {@code notifications.title} is VARCHAR(255). */
    static final int TITLE_MAX = 255;

    private final NotificationRepository notifications;
    private final NotificationPreferencesRepository prefs;
    private final PredictorAlertStore alerts;
    private final EventRepository events;
    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties emailProps;
    private final Clock clock;

    public ReforecastAlertNotifier(NotificationRepository notifications, NotificationPreferencesRepository prefs,
                                   PredictorAlertStore alerts, EventRepository events, OrganizationRepository orgs,
                                   UserRepository users, EmailService email, EmailTemplateRenderer renderer,
                                   EmailProperties emailProps, Clock clock) {
        this.notifications = notifications;
        this.prefs = prefs;
        this.alerts = alerts;
        this.events = events;
        this.orgs = orgs;
        this.users = users;
        this.email = email;
        this.renderer = renderer;
        this.emailProps = emailProps;
        this.clock = clock;
    }

    /** One dashboard notification for a band crossing old → new, unless opted out or the day is already claimed. */
    public void notifyBandChange(Event event, ProjectionBand oldBand, ProjectionBand newBand,
                                 ReforecastResult result) {
        // A failed lookup or claim must never fail the recompute that called us.
        try {
            if (!wants(event)) {
                log.info("[reforecast-alert] event {} band {} -> {}: creator opted out of predictor alerts",
                        event.getId(), oldBand, newBand);
                return;
            }
            if (!alerts.claim(event.getId(), alertDay(event), PredictorAlertStore.KIND_BAND, null)) {
                log.info("[reforecast-alert] event {} band {} -> {}: already alerted today",
                        event.getId(), oldBand, newBand);
                return;
            }
        } catch (Exception ex) {
            log.warn("[reforecast-alert] event {} band {} -> {}: alert gate failed; no alert",
                    event.getId(), oldBand, newBand, ex);
            return;
        }
        String eventName = event.getName() == null || event.getName().isBlank() ? "your event" : event.getName();

        Notification n = new Notification();
        n.setUserId(event.getCreatedBy());
        n.setKind("predictor.trajectory." + newBand.wire().toLowerCase());
        n.setTitle("Sales trajectory changed for " + eventName);
        n.setBody(body(oldBand, newBand, result));
        n.setLink("/events/" + event.getId());
        notifications.save(n);

        log.info("[reforecast-alert] event {} band {} -> {} (one dashboard notification)",
                event.getId(), oldBand, newBand);
    }

    /** In-app row plus email for a radar run whose verdict worsened; the run has already committed. */
    public void notifyRadarWorsened(RadarAlertRule.Alert a) {
        Event e = events.findById(a.eventId()).orElse(null);
        if (e == null || e.getDeletedAt() != null) {
            log.info("[radar-alert] event {} gone; no alert", a.eventId());
            return;
        }
        if (!wants(e)) {
            log.info("[radar-alert] event {}: creator opted out of predictor alerts", e.getId());
            return;
        }
        // Copy is built before the claim, so a bad value cannot use up the day.
        String locale = users.findById(e.getCreatedBy()).map(User::getLocale).orElse(null);
        String name = eventName(e, locale);
        String from = verdictWord(a.fromVerdict(), locale);
        String to = verdictWord(a.toVerdict(), locale);
        String night = night(a.night(), locale);
        if (!alerts.claim(e.getId(), alertDay(e), PredictorAlertStore.KIND_RADAR, a.runCheckId())) {
            log.info("[radar-alert] event {}: already alerted today", e.getId());
            return;
        }

        Notification n = new Notification();
        n.setUserId(e.getCreatedBy());
        n.setKind(RADAR_KIND);
        n.setTitle(fitTitle(name, from, to, locale));
        n.setBody(radarBody(night, a.fromRisk(), a.toRisk(), locale));
        n.setLink("/events/" + e.getId() + "/predictor");
        notifications.save(n);

        Organization org = orgs.findById(e.getOrgId()).orElse(null);
        String recipient = org == null ? null : org.getContactEmail();
        if (recipient == null || recipient.isBlank()) {
            log.warn("[radar-alert] no contact email for org {}; event {} alerted in-app only", e.getOrgId(), e.getId());
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("eventName", name);
        values.put("night", night);
        values.put("fromVerdict", from);
        values.put("toVerdict", to);
        values.put("fromRisk", String.valueOf(a.fromRisk()));
        values.put("toRisk", String.valueOf(a.toRisk()));
        values.put("dashboardUrl", emailProps.getAppBaseUrl() + "/events/" + e.getId() + "/predictor");
        try {
            EmailTemplateRenderer.Rendered r = renderer.render(RADAR_TEMPLATE, locale, values);
            email.send(recipient, title(name, from, to, locale), r.html(), r.text());
            log.info("[radar-alert] event {} emailed to {}", e.getId(), LogSafe.email(recipient));
        } catch (Exception ex) {
            log.warn("[radar-alert] email failed for event {}; in-app row kept", e.getId(), ex);
        }
    }

    private boolean wants(Event e) {
        return prefs.findById(e.getCreatedBy()).map(NotificationPreferences::isPredictorShift).orElse(true);
    }

    /** The event's local calendar day; both alert kinds use it so they share one day. */
    LocalDate alertDay(Event e) {
        return clock.instant().atZone(zoneOf(e.getTimezone())).toLocalDate();
    }

    private static ZoneId zoneOf(String raw) {
        if (raw == null || raw.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(raw);
        } catch (DateTimeException ex) {
            return ZoneOffset.UTC;
        }
    }

    // --- radar copy ---

    static String title(String event, String from, String to, String locale) {
        return EmailLocale.choose(locale,
                event + ": date check changed (" + from + " → " + to + ")",
                event + ": cambió la revisión de la fecha (" + from + " → " + to + ")",
                event + "\u00a0: la vérification de la date a changé (" + from + " → " + to + ")",
                event + ": перевірка дати змінилась (" + from + " → " + to + ")");
    }

    /** The title cut to the column by shortening the event name with an ellipsis. */
    static String fitTitle(String event, String from, String to, String locale) {
        String full = title(event, from, to, locale);
        if (full.length() <= TITLE_MAX) return full;
        int keep = TITLE_MAX - (full.length() - event.length()) - 1;
        if (keep > 0 && Character.isHighSurrogate(event.charAt(keep - 1))) keep--;
        return title(event.substring(0, Math.max(0, keep)) + "…", from, to, locale);
    }

    static String radarBody(String night, int fromRisk, int toRisk, String locale) {
        String risk = fromRisk + "/10 → " + toRisk + "/10";
        return EmailLocale.choose(locale,
                night + " · Date risk " + risk + ". Open the Predictor tab to see what we found.",
                night + " · Riesgo de la fecha " + risk + ". Abre la pestaña Predictor para ver lo que encontramos.",
                night + " · Risque de date " + risk + ". Ouvrez l’onglet Prédicteur pour voir ce que nous avons trouvé.",
                night + " · Ризик дати " + risk + ". Відкрийте вкладку «Прогноз», щоб побачити, що ми знайшли.");
    }

    static String verdictWord(String verdict, String locale) {
        return switch (verdict == null ? "" : verdict) {
            case "good" -> EmailLocale.choose(locale, "Good", "Buena", "Bonne", "Добра");
            case "adjust" -> EmailLocale.choose(locale, "Adjust", "Ajustar", "À ajuster", "Підлаштуйтесь");
            case "move" -> EmailLocale.choose(locale, "Move", "Cambiar", "À déplacer", "Перенесіть");
            default -> throw new IllegalArgumentException("not an alertable verdict: " + verdict);
        };
    }

    /** The night as a weekday, day and month in the recipient's language, first letter upper-cased. */
    static String night(LocalDate night, String locale) {
        String lang = EmailLocale.normalize(locale);
        Locale loc = Locale.forLanguageTag(lang);
        String pattern = switch (lang) {
            case "es" -> "EEEE d 'de' MMMM";
            case "uk" -> "EEEE, d MMMM";
            default -> "EEEE d MMMM";
        };
        String s = night.format(DateTimeFormatter.ofPattern(pattern, loc));
        return s.substring(0, 1).toUpperCase(loc) + s.substring(1);
    }

    static String eventName(Event e, String locale) {
        String n = e.getName();
        if (n != null && !n.isBlank()) return n;
        return EmailLocale.choose(locale, "Your event", "Tu evento", "Votre événement", "Ваша подія");
    }

    // --- band copy ---

    private static String body(ProjectionBand oldBand, ProjectionBand newBand, ReforecastResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Was ").append(oldBand.phrase()).append("; now ").append(newBand.phrase()).append('.');
        if (result != null && result.projectedFinalRange() != null) {
            sb.append(" Projected ").append(result.projectedFinalRange().low())
                    .append("–").append(result.projectedFinalRange().high()).append(" sold.");
        }
        return sb.toString();
    }
}

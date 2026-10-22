package com.imin.iminapi.marketing.stock;

import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.marketing.model.StockAlertKind;
import com.imin.iminapi.marketing.model.StockAlertReason;
import com.imin.iminapi.model.Notification;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * Bell entries stock_alert_ready / _scheduled / _withdrawn in the owner's language, linking to the card; each write is
 * its own transaction and never throws.
 */
@Component
public class StockAlertNotifier {

    public static final String KIND_READY = "stock_alert_ready";
    public static final String KIND_SCHEDULED = "stock_alert_scheduled";
    public static final String KIND_WITHDRAWN = "stock_alert_withdrawn";

    private static final Logger log = LoggerFactory.getLogger(StockAlertNotifier.class);

    private final UserRepository users;
    private final NotificationRepository notifications;
    private final TransactionTemplate requiresNew;

    public StockAlertNotifier(UserRepository users, NotificationRepository notifications,
                              PlatformTransactionManager transactionManager) {
        this.users = users;
        this.notifications = notifications;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A ready alert waits for "Review and send". */
    public void ready(UUID orgId, UUID eventId, String eventName, StockAlertKind kind, StockFacts f) {
        write(orgId, cardLink(eventId), KIND_READY, lang -> new String[] {
                EmailLocale.choose(lang, "Stock alert ready: ", "Alerta de entradas lista: ",
                        "Alerte billets prête : ", "Сповіщення про квитки готове: ") + f.tierName(),
                event(eventName) + fact(kind, f, lang) + " " + EmailLocale.choose(lang,
                        "Review it on the Promote tab before it goes out.",
                        "Revísala en la pestaña Promocionar antes de enviarla.",
                        "Relisez-la dans l’onglet Promouvoir avant l’envoi.",
                        "Перегляньте його на вкладці «Просування» перед надсиланням.")});
    }

    /** IMIN scheduled an alert on its own; the card's Stop cancels it until {@code at}. */
    public void scheduled(UUID orgId, UUID eventId, String eventName, String tierName, Instant at, String orgTimezone) {
        write(orgId, cardLink(eventId), KIND_SCHEDULED, lang -> new String[] {
                EmailLocale.choose(lang, "Stock alert scheduled: ", "Alerta de entradas programada: ",
                        "Alerte billets programmée : ", "Сповіщення про квитки заплановано: ") + tierName,
                event(eventName) + EmailLocale.choose(lang, "Goes out ", "Sale el ", "Part le ", "Надійде ")
                        + StockFactTokens.date(at, orgTimezone, lang) + " (" + zoneLabel(orgTimezone) + "). "
                        + EmailLocale.choose(lang,
                        "You can stop it on the Promote tab until then.",
                        "Puedes detenerla en la pestaña Promocionar hasta entonces.",
                        "Vous pouvez l’arrêter dans l’onglet Promouvoir d’ici là.",
                        "До того часу його можна зупинити на вкладці «Просування».")});
    }

    /** Auto mode made the draft but could not schedule it; the entry opens the draft for the owner to send. */
    public void draftWaiting(UUID orgId, UUID eventId, String eventName, String tierName, UUID campaignId) {
        write(orgId, "/marketing/campaigns/" + campaignId + "/edit?step=review", KIND_READY, lang -> new String[] {
                EmailLocale.choose(lang, "Stock alert draft waiting: ", "Borrador de alerta de entradas pendiente: ",
                        "Brouillon d’alerte billets en attente\u00a0: ", "Чернетка сповіщення про квитки чекає: ") + tierName,
                event(eventName) + EmailLocale.choose(lang,
                        "IMIN made the draft but could not schedule it on its own. Review it and send it yourself.",
                        "IMIN creó el borrador pero no pudo programarlo por su cuenta. Revísalo y envíalo tú.",
                        "IMIN a créé le brouillon mais n’a pas pu le programmer seul. Relisez-le et envoyez-le vous-même.",
                        "IMIN створив чернетку, але не зміг запланувати її сам. Перегляньте й надішліть її самі.")});
    }

    /** The send guard stopped an alert because a quoted fact changed. */
    public void withdrawn(UUID orgId, UUID eventId, String eventName, String tierName, String reason) {
        write(orgId, cardLink(eventId), KIND_WITHDRAWN, lang -> new String[] {
                EmailLocale.choose(lang, "Stock alert stopped: ", "Alerta de entradas detenida: ",
                        "Alerte billets arrêtée : ", "Сповіщення про квитки зупинено: ") + tierName,
                event(eventName) + reason(reason, lang) + " " + EmailLocale.choose(lang,
                        "Nothing more goes out.", "No se envía nada más.", "Plus rien ne part.",
                        "Більше нічого не надсилається.")});
    }

    private interface Text {
        String[] of(String lang);
    }

    private static String cardLink(UUID eventId) {
        return "/events/" + eventId + "/promote#stock-alerts";
    }

    private void write(UUID orgId, String link, String kind, Text text) {
        try {
            // The transaction ends inside the try, so a rollback-only mark or a failed commit flush is caught too.
            requiresNew.executeWithoutResult(status -> {
                User owner = users.findByOrgIdOrderByCreatedAtAsc(orgId).stream()
                        .filter(u -> u.getRole() == UserRole.OWNER)
                        .findFirst().orElse(null);
                if (owner == null) return;
                String[] t = text.of(EmailLocale.normalize(owner.getLocale()));
                Notification n = new Notification();
                n.setUserId(owner.getId());
                n.setKind(kind);
                n.setTitle(t[0]);
                n.setBody(t[1]);
                n.setLink(link);
                notifications.save(n);
            });
        } catch (Exception e) {
            log.warn("[stock-alerts] {} notification failed for org {}: {}", kind, orgId, LogSafe.redact(e.getMessage()));
        }
    }

    private static String event(String eventName) {
        return eventName == null || eventName.isBlank() ? "" : eventName.trim() + " · ";
    }

    private static String fact(StockAlertKind kind, StockFacts f, String lang) {
        if (kind == StockAlertKind.LAST_TICKETS) {
            String left = StockFactTokens.left(f.remaining(), lang, StockFactTokens.javaLocale(lang));
            return Character.toUpperCase(left.charAt(0)) + left.substring(1) + ".";
        }
        String at = f.closesAt() == null ? "" : StockFactTokens.date(f.closesAt(), f.timezone(), lang);
        return EmailLocale.choose(lang, "On sale until ", "A la venta hasta el ", "En vente jusqu’au ",
                "У продажу до ") + at + ".";
    }

    static String reason(String reason, String lang) {
        if (reason == null) reason = "";
        return switch (reason) {
            case StockAlertReason.SOLD_OUT -> EmailLocale.choose(lang, "The tier sold out.", "La entrada se agotó.",
                    "Le tarif est épuisé.", "Квитки цієї категорії розпродано.");
            case StockAlertReason.TIER_OFF -> EmailLocale.choose(lang, "The tier is not on sale.",
                    "La entrada no está a la venta.", "Le tarif n’est pas en vente.", "Ця категорія не продається.");
            case StockAlertReason.MORE_LEFT -> EmailLocale.choose(lang, "More tickets are left than the alert limit.",
                    "Quedan más entradas que el límite de la alerta.", "Il reste plus de billets que le seuil de l’alerte.",
                    "Квитків залишилося більше, ніж поріг сповіщення.");
            case StockAlertReason.DEADLINE_PASSED -> EmailLocale.choose(lang, "The tier stopped selling.",
                    "La entrada dejó de venderse.", "Le tarif n’est plus en vente.", "Продаж цієї категорії завершено.");
            case StockAlertReason.DEADLINE_MOVED -> EmailLocale.choose(lang, "The tier's end time changed.",
                    "Cambió la hora de fin de la entrada.", "L’heure de fin du tarif a changé.",
                    "Час завершення продажу категорії змінився.");
            case StockAlertReason.NEXT_NOT_HIGHER -> EmailLocale.choose(lang, "The price no longer goes up.",
                    "El precio ya no sube.", "Le prix n’augmente plus.", "Ціна більше не зростає.");
            case StockAlertReason.NO_NEXT_TIER -> EmailLocale.choose(lang, "No other tier is on sale after the change.",
                    "No hay otra entrada a la venta después del cambio.", "Aucun autre tarif n’est en vente après le changement.",
                    "Після зміни інша категорія не продається.");
            case StockAlertReason.NEXT_TIER_UNCLEAR -> EmailLocale.choose(lang,
                    "Two tiers on sale after the change cost the same, so the next one can't be named.",
                    "Dos entradas a la venta después del cambio cuestan lo mismo; no se puede nombrar la siguiente.",
                    "Deux tarifs en vente après le changement coûtent pareil\u00a0; le suivant ne peut pas être nommé.",
                    "Дві категорії після зміни коштують однаково, тому наступну не можна назвати.");
            case StockAlertReason.EVENT_NOT_ON_SALE -> EmailLocale.choose(lang, "The event is not on sale.",
                    "El evento no está a la venta.", "L’événement n’est pas en vente.", "Подія не продається.");
            case StockAlertReason.PRICE_CHANGED -> EmailLocale.choose(lang, "A price the email quoted changed.",
                    "Cambió un precio que citaba el email.", "Un prix cité dans l’e-mail a changé.",
                    "Змінилася ціна, вказана в листі.");
            default -> EmailLocale.choose(lang, "A fact the email quoted changed.", "Cambió un dato que citaba el email.",
                    "Une information citée dans l’e-mail a changé.", "Змінився факт, вказаний у листі.");
        };
    }

    private static String zoneLabel(String timezone) {
        return timezone == null || timezone.isBlank() ? "UTC" : timezone;
    }
}

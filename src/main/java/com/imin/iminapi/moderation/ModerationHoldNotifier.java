package com.imin.iminapi.moderation;

import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.ModerationHold;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.ModerationHoldRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The organizer's statement of reasons for a moderation hold, and the notice that it was lifted, sent to
 * {@code organizations.contact_email}: measure, scope, date, ground and facts (unless withheld), that a person
 * decided, and how to appeal. AFTER_COMMIT + {@code @Async}: nothing is sent for a hold that rolled back, and the
 * staff call never waits on Resend. Failures are logged and leave the notice stamp null, which staff can see.
 */
@Component
public class ModerationHoldNotifier {

    private static final Logger log = LoggerFactory.getLogger(ModerationHoldNotifier.class);

    public record ModerationHoldPlaced(UUID holdId) {}

    public record ModerationHoldLifted(UUID holdId) {}

    private final ModerationHoldRepository holds;
    private final OrganizationRepository orgs;
    private final EventRepository events;
    private final UserRepository users;
    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties emailProps;
    private final ModerationProperties props;
    private final AudiencePlanAccess audiencePlanAccess;
    private final Clock clock;

    public ModerationHoldNotifier(ModerationHoldRepository holds, OrganizationRepository orgs, EventRepository events,
                                  UserRepository users, EmailService email, EmailTemplateRenderer renderer,
                                  EmailProperties emailProps, ModerationProperties props,
                                  AudiencePlanAccess audiencePlanAccess, Clock clock) {
        this.holds = holds;
        this.orgs = orgs;
        this.events = events;
        this.users = users;
        this.email = email;
        this.renderer = renderer;
        this.emailProps = emailProps;
        this.props = props;
        this.audiencePlanAccess = audiencePlanAccess;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("ticketEmailExecutor")
    public void onPlaced(ModerationHoldPlaced ev) {
        try {
            notifyPlaced(ev.holdId());
        } catch (Exception e) {
            log.warn("[moderation] placed notice for hold {} failed: {}", ev.holdId(), e.getMessage(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("ticketEmailExecutor")
    public void onLifted(ModerationHoldLifted ev) {
        try {
            notifyLifted(ev.holdId());
        } catch (Exception e) {
            log.warn("[moderation] lifted notice for hold {} failed: {}", ev.holdId(), e.getMessage(), e);
        }
    }

    void notifyPlaced(UUID holdId) {
        Context c = load(holdId);
        if (c == null) return;
        ModerationHold h = c.hold;
        String title = title(h, c.locale);

        Map<String, String> values = new LinkedHashMap<>();
        values.put("title", title);
        values.put("scopeLine", scopeLine(c));
        values.put("sinceLine", EmailLocale.choose(c.locale, "Since {date}", "Desde el {date}", "Depuis le {date}",
                "З {date}").replace("{date}", date(h.getPlacedAt(), c)));
        values.put("effectLines", effectLines(h, c.locale));
        if (h.isReasonWithheld()) {
            values.put("groundLine", "");
            values.put("reasonLine", EmailLocale.choose(c.locale,
                    "imin can't tell you the reason, because the law or an authority does not allow it.",
                    "imin no puede indicarte el motivo porque la ley o una autoridad no lo permite.",
                    "imin ne peut pas vous en donner le motif, car la loi ou une autorité ne le permet pas.",
                    "imin не може повідомити причину, бо цього не дозволяє закон або орган влади."));
        } else {
            values.put("groundLine", EmailLocale.choose(c.locale, "Ground:", "Motivo:", "Motif :", "Підстава:")
                    + " " + groundLabel(h.getGround(), c.locale));
            values.put("reasonLine", EmailLocale.choose(c.locale, "Details from imin:", "Detalles de imin:",
                    "Précisions d'imin :", "Пояснення від imin:") + " " + h.getFacts());
        }
        values.put("decidedLine", decidedLine(c.locale));
        values.put("appealLine", EmailLocale.choose(c.locale,
                "To contest it, email {email} within 14 days of our notice, with your evidence. We answer within 7 working days.",
                "Para impugnarla, escribe a {email} en los 14 días siguientes a nuestro aviso, con tus pruebas. Respondemos en 7 días hábiles.",
                "Pour la contester, écrivez à {email} dans les 14 jours suivant notre notification, avec vos justificatifs. Nous répondons sous 7 jours ouvrés.",
                "Щоб оскаржити його, напишіть на {email} протягом 14 днів після нашого повідомлення й додайте докази. Ми відповідаємо протягом 7 робочих днів.")
                .replace("{email}", props.getAppealEmail()));
        values.put("dashboardUrl", emailProps.getAppBaseUrl());

        String subject = title + " – " + c.scopeName;
        if (send(c, "moderation-hold-placed", subject, values, "placed")) {
            holds.stampNotice(holdId, now());
        }
    }

    void notifyLifted(UUID holdId) {
        Context c = load(holdId);
        if (c == null) return;
        ModerationHold h = c.hold;

        Map<String, String> values = new LinkedHashMap<>();
        values.put("title", EmailLocale.choose(c.locale, "imin has lifted this measure.", "imin ha levantado esta medida.",
                "imin a levé cette mesure.", "imin скасувала цей захід."));
        values.put("measureLine", EmailLocale.choose(c.locale, "Measure:", "Medida:", "Mesure :", "Захід:")
                + " " + title(h, c.locale));
        values.put("scopeLine", scopeLine(c));
        List<String> effects = new ArrayList<>();
        if (h.isSuspendSales()) {
            effects.add(EmailLocale.choose(c.locale,
                    "Ticket sales can resume, unless another measure still applies.",
                    "Las ventas pueden reanudarse, salvo que siga vigente otra medida.",
                    "La vente peut reprendre, sauf si une autre mesure s'applique encore.",
                    "Продаж може відновитися, якщо не діє інший захід."));
        }
        if (h.isHoldPayouts()) {
            effects.add(EmailLocale.choose(c.locale,
                    "Payouts can start again from the next daily payout run, if the usual payout conditions are met.",
                    "Los pagos pueden reanudarse a partir de la siguiente tanda diaria si se cumplen las condiciones habituales de pago.",
                    "Les versements peuvent reprendre dès le prochain passage quotidien, si les conditions habituelles de versement sont remplies.",
                    "Виплати можуть відновитися з наступного щоденного запуску, якщо виконано звичайні умови виплат."));
        }
        // Every hold holds the campaigns in its scope; only those no other hold still covers go out now.
        boolean planSends = audiencePlanAccess.sendsEnabled();
        boolean legalAll = audiencePlanAccess.legalIdentityAllCampaigns();
        long released = h.getEventId() == null
                ? holds.countReleasedCampaignsForOrg(h.getOrgId(), planSends, legalAll)
                : holds.countReleasedCampaignsForEvent(h.getOrgId(), h.getEventId(), planSends, legalAll);
        if (released > 0L) {
            effects.add(EmailLocale.choose(c.locale,
                    "Paused campaigns will now go out as scheduled — check them before they send.",
                    "Las campañas en pausa se enviarán ahora según lo programado: revísalas antes de que salgan.",
                    "Les campagnes suspendues vont maintenant partir comme prévu — vérifiez-les avant leur envoi.",
                    "Призупинені кампанії тепер буде надіслано за розкладом — перевірте їх перед відправкою."));
        }
        values.put("effectLines", String.join(" ", effects));
        values.put("otherActiveLine", holds.countActiveByOrgId(h.getOrgId()) > 0
                ? EmailLocale.choose(c.locale,
                    "Other measures on your account are still in place. Your dashboard lists them.",
                    "Otras medidas sobre tu cuenta siguen vigentes. Las verás en tu panel.",
                    "D'autres mesures restent en place sur votre compte. Votre tableau de bord les liste.",
                    "Інші заходи щодо вашого акаунта ще діють. Їх перелік є у вашій панелі.")
                : "");
        values.put("decidedLine", decidedLine(c.locale));
        values.put("dashboardUrl", emailProps.getAppBaseUrl());

        String subject = EmailLocale.choose(c.locale, "Measure lifted – {scopeName}", "Medida levantada – {scopeName}",
                "Mesure levée – {scopeName}", "Захід скасовано – {scopeName}").replace("{scopeName}", c.scopeName);
        if (send(c, "moderation-hold-lifted", subject, values, "lifted")) {
            holds.stampLiftNotice(holdId, now());
        }
    }

    private Context load(UUID holdId) {
        ModerationHold h = holds.findById(holdId).orElse(null);
        if (h == null) {
            log.warn("[moderation] hold {} not found — no notice", holdId);
            return null;
        }
        Organization org = orgs.findById(h.getOrgId()).orElse(null);
        if (org == null) {
            log.warn("[moderation] org {} of hold {} not found — no notice", h.getOrgId(), holdId);
            return null;
        }
        Context c = new Context();
        c.hold = h;
        c.org = org;
        Event event = h.getEventId() == null ? null
                : events.findById(h.getEventId()).filter(e -> e.getDeletedAt() == null).orElse(null);
        c.eventName = event == null || event.getName() == null || event.getName().isBlank() ? null : event.getName();
        c.scopeName = h.getEventId() != null && c.eventName != null ? c.eventName : org.getName();
        String creatorLocale = event == null || event.getCreatedBy() == null ? null
                : users.findById(event.getCreatedBy()).map(User::getLocale).orElse(null);
        c.locale = creatorLocale != null ? creatorLocale : users.findByOrgIdAndDisabledAtIsNullOrderByCreatedAtAsc(org.getId())
                .stream().filter(u -> u.getRole() == UserRole.OWNER).map(User::getLocale).findFirst().orElse(null);
        return c;
    }

    private boolean send(Context c, String template, String subject, Map<String, String> values, String kind) {
        String to = c.org.getContactEmail();
        if (to == null || to.isBlank()) {
            log.warn("[moderation] org {} has no contact email — {} notice for hold {} not sent",
                    c.org.getId(), kind, c.hold.getId());
            return false;
        }
        EmailTemplateRenderer.Rendered r = renderer.render(template, c.locale, values);
        email.send(to, subject, r.html(), r.text());
        log.info("[moderation] sent the {} notice for hold {} to {}", kind, c.hold.getId(), LogSafe.email(to));
        return true;
    }

    private static String title(ModerationHold h, String locale) {
        if (h.isSuspendSales() && h.isHoldPayouts()) {
            return EmailLocale.choose(locale, "Ticket sales are paused and payouts are on hold",
                    "Las ventas de entradas están en pausa y los pagos, retenidos",
                    "La vente de billets est suspendue et les versements sont bloqués",
                    "Продаж квитків призупинено, а виплати затримано");
        }
        if (h.isSuspendSales()) {
            return EmailLocale.choose(locale, "Ticket sales are paused", "Las ventas de entradas están en pausa",
                    "La vente de billets est suspendue", "Продаж квитків призупинено");
        }
        return EmailLocale.choose(locale, "Payouts are on hold", "Los pagos están retenidos",
                "Les versements sont bloqués", "Виплати затримано");
    }

    private static String scopeLine(Context c) {
        if (c.hold.getEventId() == null) {
            return EmailLocale.choose(c.locale, "This applies to all your events.", "Afecta a todos tus eventos.",
                    "Cela concerne tous vos événements.", "Це стосується всіх ваших подій.");
        }
        if (c.eventName == null) {
            return EmailLocale.choose(c.locale, "This applies to one of your events.", "Afecta a uno de tus eventos.",
                    "Cela concerne l'un de vos événements.", "Це стосується однієї з ваших подій.");
        }
        return EmailLocale.choose(c.locale, "This applies to {event}.", "Afecta a {event}.", "Cela concerne {event}.",
                "Це стосується: {event}.").replace("{event}", c.eventName);
    }

    private static String effectLines(ModerationHold h, String locale) {
        List<String> out = new ArrayList<>();
        if (h.isSuspendSales()) {
            out.add(EmailLocale.choose(locale,
                    "Buyers can't start new purchases. Tickets already sold are not affected.",
                    "Los compradores no pueden iniciar nuevas compras. Las entradas ya vendidas no se ven afectadas.",
                    "Les acheteurs ne peuvent pas lancer de nouvel achat. Les billets déjà vendus ne sont pas concernés.",
                    "Покупці не можуть почати нову покупку. Уже продані квитки це не зачіпає."));
        }
        if (h.isHoldPayouts()) {
            out.add(EmailLocale.choose(locale,
                    "No new payout starts while this hold is in place.",
                    "No se inicia ningún pago nuevo mientras dure esta retención.",
                    "Aucun nouveau versement ne part tant que ce blocage est en place.",
                    "Поки діє це затримання, нові виплати не починаються."));
        }
        // Any hold, whatever its measure, keeps the campaigns in its scope from sending (CampaignRepository.claimDue).
        out.add(EmailLocale.choose(locale,
                "Scheduled email campaigns are paused while this hold is in place and go out after it is lifted, unless another measure still applies or you cancel them.",
                "Las campañas de email programadas quedan en pausa mientras dure esta retención y se envían cuando se levante, salvo que siga vigente otra medida o que las canceles.",
                "Les campagnes e-mail programmées sont suspendues tant que ce blocage est en place et partent une fois qu'il est levé, sauf si une autre mesure s'applique encore ou si vous les annulez.",
                "Заплановані email-кампанії призупинено, поки діє це затримання. Їх буде надіслано після його зняття, якщо не діє інший захід і ви їх не скасуєте."));
        return String.join(" ", out);
    }

    private static String decidedLine(String locale) {
        return EmailLocale.choose(locale, "A person at imin took this decision.",
                "Esta decisión la ha tomado una persona de imin.",
                "Cette décision a été prise par une personne d'imin.",
                "Це рішення ухвалила людина з команди imin.");
    }

    static String groundLabel(String ground, String locale) {
        return switch (ground) {
            case "aup_breach" -> EmailLocale.choose(locale, "A breach of the Acceptable Use Policy",
                    "Incumplimiento de la Política de uso aceptable",
                    "Manquement à la Politique d'utilisation acceptable",
                    "Порушення Політики прийнятного використання");
            case "terms_breach" -> EmailLocale.choose(locale, "A breach of the Organizer Terms",
                    "Incumplimiento de las Condiciones para organizadores",
                    "Manquement aux Conditions organisateur", "Порушення Умов для організаторів");
            case "fraud" -> EmailLocale.choose(locale, "Suspected fraud", "Sospecha de fraude", "Suspicion de fraude",
                    "Підозра на шахрайство");
            case "safety" -> EmailLocale.choose(locale, "A safety risk", "Riesgo para la seguridad",
                    "Risque pour la sécurité", "Ризик для безпеки");
            case "authority_order" -> EmailLocale.choose(locale, "An order from an authority", "Orden de una autoridad",
                    "Décision d'une autorité", "Розпорядження органу влади");
            case "reported_event" -> EmailLocale.choose(locale, "A report about an event, under review",
                    "Denuncia sobre un evento, en revisión", "Signalement d'un événement, en cours d'examen",
                    "Скарга на подію, що перевіряється");
            default -> throw new IllegalStateException("unknown ground " + ground);
        };
    }

    /** The placement date in the org's time zone (UTC when blank or invalid), in the notice's language. */
    private static String date(Instant at, Context c) {
        ZoneId zone;
        try {
            String tz = c.org.getTimezone();
            zone = tz == null || tz.isBlank() ? ZoneOffset.UTC : ZoneId.of(tz);
        } catch (DateTimeException e) {
            zone = ZoneOffset.UTC;
        }
        String tag = EmailLocale.choose(c.locale, "en", "es", "fr", "uk");
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.forLanguageTag(tag))
                .withZone(zone).format(at);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static final class Context {
        private ModerationHold hold;
        private Organization org;
        private String eventName;
        private String scopeName;
        private String locale;
    }
}

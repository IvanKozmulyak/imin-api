package com.imin.iminapi.payout;

import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Notification;
import com.imin.iminapi.model.NotificationPreferences;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotificationPreferencesRepository;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.util.LogSafe;
import com.imin.iminapi.util.MoneyFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The organizer-facing half of a payout: an in-app {@link Notification} plus an email, for the
 * two things they can do nothing about unless we tell them — a payout that is parked
 * ({@link PayoutBlockedEvent}) and a payout that has settled ({@link PayoutArrivedEvent}).
 *
 * <p>Same shape as {@code SalesMilestoneNotifier}: {@code AFTER_COMMIT} + {@code @Async} so the
 * nightly sweep and the Stripe webhook ack are never blocked on Resend, and so nothing sends if
 * the payout transaction rolls back. Failures are logged and swallowed — there is nothing left
 * to retry against by then.
 *
 * <p><b>Only the arrival is preference-gated</b> ({@code payout_arrived}). A blocked payout is
 * an operational alert: money the organizer is owed is not moving and only they can unblock it,
 * so it is not something a notification setting may silence.
 */
@Component
public class OrganizerPayoutNotifier {

    private static final Logger log = LoggerFactory.getLogger(OrganizerPayoutNotifier.class);

    private final PayoutRunRepository payoutRuns;
    private final EventRepository events;
    private final OrganizationRepository orgs;
    private final NotificationRepository notifications;
    private final NotificationPreferencesRepository prefs;
    private final UserRepository users;
    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties emailProps;

    public OrganizerPayoutNotifier(PayoutRunRepository payoutRuns,
                                   EventRepository events,
                                   OrganizationRepository orgs,
                                   NotificationRepository notifications,
                                   NotificationPreferencesRepository prefs,
                                   UserRepository users,
                                   EmailService email,
                                   EmailTemplateRenderer renderer,
                                   EmailProperties emailProps) {
        this.payoutRuns = payoutRuns;
        this.events = events;
        this.orgs = orgs;
        this.notifications = notifications;
        this.prefs = prefs;
        this.users = users;
        this.email = email;
        this.renderer = renderer;
        this.emailProps = emailProps;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("ticketEmailExecutor")
    public void onPayoutBlocked(PayoutBlockedEvent ev) {
        try {
            notifyBlocked(ev.runId());
        } catch (Exception e) {
            log.warn("[payout] blocked-payout notification failed for run {}: {}",
                    ev.runId(), e.getMessage(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("ticketEmailExecutor")
    public void onPayoutArrived(PayoutArrivedEvent ev) {
        try {
            notifyArrived(ev.runId());
        } catch (Exception e) {
            log.warn("[payout] arrival notification failed for run {}: {}",
                    ev.runId(), e.getMessage(), e);
        }
    }

    void notifyBlocked(UUID runId) {
        Context c = load(runId, "blocked");
        if (c == null) return;

        boolean noBank = PayoutBlockReason.NO_BANK_ACCOUNT.equals(c.run.getFailureReason());
        String title = noBank
                ? "Add a bank account to receive your " + c.eventName + " payout"
                : "We could not send your " + c.eventName + " payout";
        String body = noBank
                ? c.amountFormatted + " is ready to pay out, but your Stripe account has no bank "
                        + "account attached. Add one and we'll send it on the next run."
                : c.amountFormatted + " could not be paid out after " + c.run.getAttempt()
                        + " attempts (last error: " + reasonDetail(c.run) + "). We've stopped "
                        + "retrying — check your payout details in Stripe.";
        saveInApp(c, "payout.blocked", title, body);

        Map<String, String> values = new LinkedHashMap<>();
        values.put("eventName", c.eventName);
        values.put("amountFormatted", c.amountFormatted);
        values.put("blockedReason", blockedReason(noBank, c));
        values.put("nextStep", nextStep(noBank, c.locale));
        values.put("dashboardUrl", c.dashboardUrl);
        send(c, "payout-blocked", blockedSubject(noBank, c), values, "blocked");
    }

    void notifyArrived(UUID runId) {
        Context c = load(runId, "arrived");
        if (c == null) return;

        // The only preference-gated payout notice: this one is good news, not an alert.
        boolean wants = c.organizerUserId == null || prefs.findById(c.organizerUserId)
                .map(NotificationPreferences::isPayoutArrived)
                .orElse(true);   // default-on, matching the entity default
        if (!wants) {
            log.info("[payout] user {} opted out of payout-arrived — run {} not notified",
                    c.organizerUserId, runId);
            return;
        }

        saveInApp(c, "payout.arrived",
                "Your " + c.eventName + " payout is on its way",
                c.amountFormatted + " has left Stripe for your bank account. Banks usually take "
                        + "a day or two to show it.");

        Map<String, String> values = new LinkedHashMap<>();
        values.put("eventName", c.eventName);
        values.put("amountFormatted", c.amountFormatted);
        values.put("dashboardUrl", c.dashboardUrl);
        send(c, "payout-arrived", EmailLocale.choose(c.locale,
                "Your payout for " + c.eventName + " is on its way",
                "Tu pago de " + c.eventName + " está en camino",
                "Votre versement pour " + c.eventName + " est en route",
                "Вашу виплату за " + c.eventName + " надіслано"), values, "arrived");
    }

    /** Everything both notices need, or null when the run/event can no longer be resolved. */
    private Context load(UUID runId, String kind) {
        PayoutRun run = payoutRuns.findById(runId).orElse(null);
        if (run == null) {
            log.warn("[payout] run {} not found — skipping the {} notification", runId, kind);
            return null;
        }
        Event event = events.findById(run.getEventId()).orElse(null);
        if (event == null) {
            log.warn("[payout] event {} for run {} not found — skipping the {} notification",
                    run.getEventId(), runId, kind);
            return null;
        }

        Context c = new Context();
        c.run = run;
        c.event = event;
        c.eventName = (event.getName() == null || event.getName().isBlank()) ? "your event" : event.getName();
        c.amountFormatted = MoneyFormat.format(run.getAmountMinor(), run.getCurrency());
        c.dashboardUrl = emailProps.getAppBaseUrl() + "/events/" + event.getId();
        c.organizerUserId = event.getCreatedBy();
        c.locale = c.organizerUserId == null
                ? null : users.findById(c.organizerUserId).map(User::getLocale).orElse(null);
        return c;
    }

    /** An event with no creator has no organizer user, and a Notification row needs one. */
    private void saveInApp(Context c, String kind, String title, String body) {
        if (c.organizerUserId == null) return;
        Notification n = new Notification();
        n.setUserId(c.organizerUserId);
        n.setKind(kind);
        n.setTitle(title);
        n.setBody(body);
        n.setLink("/events/" + c.event.getId());
        notifications.save(n);
    }

    private void send(Context c, String template, String subject, Map<String, String> values, String kind) {
        Organization org = orgs.findById(c.run.getOrgId()).orElse(null);
        String to = org == null ? null : org.getContactEmail();
        if (to == null || to.isBlank()) {
            log.warn("[payout] no contact email for org {} — run {} notified in-app only ({})",
                    c.run.getOrgId(), c.run.getId(), kind);
            return;
        }
        EmailTemplateRenderer.Rendered r = renderer.render(template, c.locale, values);
        email.send(to, subject, r.html(), r.text());
        log.info("[payout] sent the {} notification for run {} to {}",
                kind, c.run.getId(), LogSafe.email(to));
    }

    private static String blockedSubject(boolean noBank, Context c) {
        return noBank
                ? EmailLocale.choose(c.locale,
                    "Add a bank account to receive your " + c.eventName + " payout",
                    "Añade una cuenta bancaria para recibir tu pago de " + c.eventName,
                    "Ajoutez un compte bancaire pour recevoir votre versement pour " + c.eventName,
                    "Додайте банківський рахунок, щоб отримати виплату за " + c.eventName)
                : EmailLocale.choose(c.locale,
                    "Action needed: we could not send your " + c.eventName + " payout",
                    "Acción necesaria: no pudimos enviar tu pago de " + c.eventName,
                    "Action requise : nous n'avons pas pu envoyer votre versement pour " + c.eventName,
                    "Потрібна дія: ми не змогли надіслати виплату за " + c.eventName);
    }

    private static String blockedReason(boolean noBank, Context c) {
        if (noBank) {
            return EmailLocale.choose(c.locale,
                    "Your Stripe account has no bank account attached, so there is nowhere to send the money.",
                    "Tu cuenta de Stripe no tiene ninguna cuenta bancaria asociada, así que no hay adónde enviar el dinero.",
                    "Aucun compte bancaire n'est rattaché à votre compte Stripe : nous n'avons nulle part où envoyer l'argent.",
                    "До вашого акаунта Stripe не прив'язано банківський рахунок, тож надсилати гроші нікуди.");
        }
        int attempts = c.run.getAttempt();
        return EmailLocale.choose(c.locale,
                "We tried to send this payout " + attempts + " times and the bank did not accept it.",
                "Intentamos enviar este pago " + attempts + " veces y el banco no lo aceptó.",
                "Nous avons tenté ce versement " + attempts + " fois et la banque ne l'a pas accepté.",
                "Ми намагалися надіслати цю виплату " + attempts + " раз(и), і банк її не прийняв.");
    }

    private static String nextStep(boolean noBank, String locale) {
        if (noBank) {
            return EmailLocale.choose(locale,
                    "Add a payout bank account in Stripe. The next payout run sends it automatically — you don't need to tell us.",
                    "Añade una cuenta bancaria de pagos en Stripe. El siguiente envío se hará automáticamente, no hace falta que nos avises.",
                    "Ajoutez un compte bancaire de versement dans Stripe. Le prochain envoi partira automatiquement, sans nous prévenir.",
                    "Додайте банківський рахунок для виплат у Stripe. Наступна виплата надійде автоматично — повідомляти нас не потрібно.");
        }
        return EmailLocale.choose(locale,
                "We have stopped retrying automatically. Check your payout details in Stripe, then contact imin support so we can restart this payout.",
                "Hemos dejado de reintentarlo automáticamente. Revisa tus datos de pago en Stripe y contacta con el soporte de imin para reiniciar este pago.",
                "Nous avons cessé les relances automatiques. Vérifiez vos coordonnées de versement dans Stripe, puis contactez le support imin pour relancer ce versement.",
                "Ми припинили автоматичні спроби. Перевірте платіжні дані у Stripe і зверніться до підтримки imin, щоб відновити цю виплату.");
    }

    /** The Stripe failure code we last saw, for the in-app row; never a fabricated one. */
    private static String reasonDetail(PayoutRun run) {
        String reason = run.getFailureReason();
        return (reason == null || reason.isBlank()) ? "none reported by Stripe" : reason;
    }

    /** Resolved recipient + copy inputs for one notification. */
    private static final class Context {
        private PayoutRun run;
        private Event event;
        private String eventName;
        private String amountFormatted;
        private String dashboardUrl;
        private UUID organizerUserId;
        private String locale;
    }
}

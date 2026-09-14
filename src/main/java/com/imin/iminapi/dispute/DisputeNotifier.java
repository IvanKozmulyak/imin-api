package com.imin.iminapi.dispute;

import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Notification;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
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
 * Tells the organizer a chargeback was opened: an in-app {@link Notification} plus an email
 * to the organization's contact inbox. Same shape as {@code SalesMilestoneNotifier} —
 * {@code AFTER_COMMIT} + {@code @Async} so the Stripe webhook ack is never blocked on Resend,
 * and so nothing sends if the ingest rolls back.
 *
 * <p><b>Not gated on a notification preference.</b> A dispute revokes a buyer's tickets and
 * freezes the org's payouts; it is an operational alert, not a marketing notice.
 *
 * <p>Fires once per dispute because {@link DisputeIngestService} publishes
 * {@link DisputeOpenedEvent} only on the transition INTO {@code OPEN} — a redelivery finds the
 * row already open and publishes nothing.
 */
@Component
public class DisputeNotifier {

    private static final Logger log = LoggerFactory.getLogger(DisputeNotifier.class);

    private final DisputeRepository disputes;
    private final EventRepository events;
    private final OrganizationRepository orgs;
    private final NotificationRepository notifications;
    private final UserRepository users;
    private final TicketRepository tickets;
    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties emailProps;

    public DisputeNotifier(DisputeRepository disputes,
                           EventRepository events,
                           OrganizationRepository orgs,
                           NotificationRepository notifications,
                           UserRepository users,
                           TicketRepository tickets,
                           EmailService email,
                           EmailTemplateRenderer renderer,
                           EmailProperties emailProps) {
        this.disputes = disputes;
        this.events = events;
        this.orgs = orgs;
        this.notifications = notifications;
        this.users = users;
        this.tickets = tickets;
        this.email = email;
        this.renderer = renderer;
        this.emailProps = emailProps;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("ticketEmailExecutor")
    public void onDisputeOpened(DisputeOpenedEvent ev) {
        try {
            notify(ev.disputeId());
        } catch (Exception e) {
            log.warn("[dispute] organizer notification failed for dispute {}: {}",
                    ev.disputeId(), e.getMessage(), e);
        }
    }

    void notify(UUID disputeId) {
        Dispute dispute = disputes.findById(disputeId).orElse(null);
        if (dispute == null) {
            log.warn("[dispute] dispute {} not found — skipping notification", disputeId);
            return;
        }

        Event event = dispute.getEventId() == null ? null : events.findById(dispute.getEventId()).orElse(null);
        String eventName = (event != null && event.getName() != null && !event.getName().isBlank())
                ? event.getName() : "your event";
        String link = event != null ? "/events/" + event.getId() : "/payouts";
        String dashboardUrl = emailProps.getAppBaseUrl() + link;
        String amountFormatted = MoneyFormat.format(dispute.getAmountMinor(), dispute.getCurrency());

        // 1) In-app notification for the organizer who created the event. An unattributed
        // dispute has no event and therefore no organizer user — email only in that case.
        UUID organizerUserId = event == null ? null : event.getCreatedBy();
        String locale = organizerUserId == null
                ? null : users.findById(organizerUserId).map(User::getLocale).orElse(null);
        int revoked = revokedTicketCount(dispute.getOrderId());
        if (organizerUserId != null) {
            Notification n = new Notification();
            n.setUserId(organizerUserId);
            n.setKind("dispute.opened");
            n.setTitle("A chargeback was opened on " + eventName);
            // ponytail: the in-app row is English throughout, title included, so it takes the
            // English consequence line rather than mixing two languages in one body.
            n.setBody(amountFormatted + " is being disputed. "
                    + consequenceLine(dispute.getOrderId(), revoked, null)
                    + " Payouts are paused until it is resolved.");
            n.setLink(link);
            notifications.save(n);
        }

        // 2) Email to the organization contact inbox.
        Organization org = orgs.findById(dispute.getOrgId()).orElse(null);
        String to = org == null ? null : org.getContactEmail();
        if (to == null || to.isBlank()) {
            log.warn("[dispute] no contact email for org {} — dispute {} notified in-app only",
                    dispute.getOrgId(), disputeId);
            return;
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put("eventName", eventName);
        values.put("amountFormatted", amountFormatted);
        values.put("dashboardUrl", dashboardUrl);
        values.put("consequenceLine", consequenceLine(dispute.getOrderId(), revoked, locale));

        EmailTemplateRenderer.Rendered r = renderer.render("dispute-opened", locale, values);
        email.send(to, subject(eventName, locale), r.html(), r.text());
        log.info("[dispute] sent chargeback notification for dispute {} to {}",
                disputeId, LogSafe.email(to));
    }

    /** Tickets on the order that are revoked right now. No order ⇒ nothing was revoked. */
    private int revokedTicketCount(UUID orderId) {
        if (orderId == null) return 0;
        return (int) tickets.findByOrderId(orderId).stream()
                .filter(t -> Ticket.STATE_REVOKED.equals(t.getState()))
                .count();
    }

    /**
     * The one sentence in this email that must describe what actually happened. A dispute we
     * could not match to an order revoked nothing; an order whose tickets were already refunded
     * had nothing left to revoke. Claiming revocation in either case is simply false.
     */
    private static String consequenceLine(UUID orderId, int revoked, String locale) {
        if (orderId == null) {
            return EmailLocale.choose(locale,
                    "We have not matched this payment to one of your orders yet, so no ticket has "
                            + "been revoked; the moment we match it, the tickets on that order are "
                            + "revoked automatically.",
                    "Todavía no hemos asociado este pago a uno de tus pedidos, así que no se ha "
                            + "revocado ninguna entrada; en cuanto lo asociemos, las entradas de ese "
                            + "pedido se revocarán automáticamente.",
                    "Nous n'avons pas encore rattaché ce paiement à l'une de vos commandes, donc "
                            + "aucun billet n'a été révoqué ; dès que ce sera fait, les billets de "
                            + "cette commande seront révoqués automatiquement.",
                    "Ми ще не зіставили цей платіж із жодним із ваших замовлень, тому жодного квитка "
                            + "не анульовано; щойно зіставимо, квитки того замовлення буде анульовано "
                            + "автоматично.");
        }
        if (revoked == 0) {
            return EmailLocale.choose(locale,
                    "Nothing was left to revoke on that order - its tickets had already been refunded.",
                    "No quedaba nada que revocar en ese pedido: sus entradas ya estaban reembolsadas.",
                    "Il n'y avait plus rien à révoquer sur cette commande : ses billets avaient déjà "
                            + "été remboursés.",
                    "У тому замовленні не було чого анулювати: за його квитки вже зроблено повернення.");
        }
        if (revoked == 1) {
            return EmailLocale.choose(locale,
                    "The ticket on that order is revoked and will not open the door.",
                    "La entrada de ese pedido está revocada y no servirá en la puerta.",
                    "Le billet de cette commande est révoqué et ne passera pas à l'entrée.",
                    "Квиток того замовлення анульовано, і на вході він не спрацює.");
        }
        // ponytail: Ukrainian carries the count in parentheses because the 2-4 / 5+ plural forms
        // would need a real pluralizer, which no other email here has.
        return EmailLocale.choose(locale,
                "The " + revoked + " tickets on that order are revoked and will not open the door.",
                "Las " + revoked + " entradas de ese pedido están revocadas y no servirán en la puerta.",
                "Les " + revoked + " billets de cette commande sont révoqués et ne passeront pas à l'entrée.",
                "Квитки того замовлення (" + revoked + ") анульовано, і на вході вони не спрацюють.");
    }

    private static String subject(String eventName, String locale) {
        return EmailLocale.choose(locale,
                "Action needed: a chargeback was opened on " + eventName,
                "Acción necesaria: se abrió un contracargo en " + eventName,
                "Action requise : une contestation a été ouverte sur " + eventName,
                "Потрібна дія: відкрито оскарження платежу для " + eventName);
    }
}

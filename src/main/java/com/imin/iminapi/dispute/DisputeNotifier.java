package com.imin.iminapi.dispute;

import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Notification;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
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
    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties emailProps;

    public DisputeNotifier(DisputeRepository disputes,
                           EventRepository events,
                           OrganizationRepository orgs,
                           NotificationRepository notifications,
                           UserRepository users,
                           EmailService email,
                           EmailTemplateRenderer renderer,
                           EmailProperties emailProps) {
        this.disputes = disputes;
        this.events = events;
        this.orgs = orgs;
        this.notifications = notifications;
        this.users = users;
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
        if (organizerUserId != null) {
            Notification n = new Notification();
            n.setUserId(organizerUserId);
            n.setKind("dispute.opened");
            n.setTitle("A chargeback was opened on " + eventName);
            n.setBody(amountFormatted + " is being disputed. The buyer's tickets are revoked and "
                    + "payouts are paused until it is resolved.");
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

        String locale = organizerUserId == null
                ? null : users.findById(organizerUserId).map(User::getLocale).orElse(null);

        Map<String, String> values = new LinkedHashMap<>();
        values.put("eventName", eventName);
        values.put("amountFormatted", amountFormatted);
        values.put("dashboardUrl", dashboardUrl);

        EmailTemplateRenderer.Rendered r = renderer.render("dispute-opened", locale, values);
        email.send(to, subject(eventName, locale), r.html(), r.text());
        log.info("[dispute] sent chargeback notification for dispute {} to {}",
                disputeId, LogSafe.email(to));
    }

    private static String subject(String eventName, String locale) {
        return EmailLocale.choose(locale,
                "Action needed: a chargeback was opened on " + eventName,
                "Acción necesaria: se abrió un contracargo en " + eventName,
                "Action requise : une contestation a été ouverte sur " + eventName,
                "Потрібна дія: відкрито оскарження платежу для " + eventName);
    }
}

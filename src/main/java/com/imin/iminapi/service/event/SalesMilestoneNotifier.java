package com.imin.iminapi.service.event;

import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Notification;
import com.imin.iminapi.model.NotificationPreferences;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotificationPreferencesRepository;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
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
 * Delivers per-tier sales-milestone notifications (50 / 80 / 100% sold) after a
 * {@link SalesMilestoneReachedEvent} is published from
 * {@link InventoryService#confirmSold(UUID)}.
 *
 * <p>Runs {@code AFTER_COMMIT} + {@code @Async} so the Stripe webhook ack is
 * never blocked on Resend latency, and so nothing sends if the sale rolls back.
 * Two channels: an in-app {@link Notification} row for the organizer user
 * ({@link Event#getCreatedBy()}) and an email to the organization's contact
 * inbox. Both are gated on the user's {@code fillMilestone} preference.
 *
 * <p>Failures are logged + swallowed — the webhook has already returned 200 to
 * Stripe, so there is nothing to retry against here.
 */
@Component
public class SalesMilestoneNotifier {

    private static final Logger log = LoggerFactory.getLogger(SalesMilestoneNotifier.class);

    private final TicketTierRepository tiers;
    private final EventRepository events;
    private final OrganizationRepository orgs;
    private final NotificationRepository notifications;
    private final NotificationPreferencesRepository prefs;
    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties emailProps;
    private final UserRepository users;

    public SalesMilestoneNotifier(TicketTierRepository tiers,
                                  EventRepository events,
                                  OrganizationRepository orgs,
                                  NotificationRepository notifications,
                                  NotificationPreferencesRepository prefs,
                                  EmailService email,
                                  EmailTemplateRenderer renderer,
                                  EmailProperties emailProps,
                                  UserRepository users) {
        this.tiers = tiers;
        this.events = events;
        this.orgs = orgs;
        this.notifications = notifications;
        this.prefs = prefs;
        this.email = email;
        this.renderer = renderer;
        this.emailProps = emailProps;
        this.users = users;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("ticketEmailExecutor")
    public void onMilestone(SalesMilestoneReachedEvent ev) {
        try {
            handle(ev);
        } catch (Exception e) {
            log.warn("[milestone] notification failed for tier {} {}%: {}",
                    ev.tierId(), ev.threshold(), e.getMessage(), e);
        }
    }

    void handle(SalesMilestoneReachedEvent ev) {
        int threshold = ev.threshold();
        TicketTier tier = tiers.findById(ev.tierId()).orElse(null);
        if (tier == null) {
            log.warn("[milestone] tier {} not found — skipping", ev.tierId());
            return;
        }
        Event event = events.findById(tier.getEventId()).orElse(null);
        if (event == null) {
            log.warn("[milestone] event {} for tier {} not found — skipping", tier.getEventId(), tier.getId());
            return;
        }

        UUID organizerUserId = event.getCreatedBy();
        boolean wants = prefs.findById(organizerUserId)
                .map(NotificationPreferences::isFillMilestone)
                .orElse(true); // default-on, matching the entity default
        if (!wants) {
            log.info("[milestone] user {} opted out of fill-milestone — skipping {}% for tier {}",
                    organizerUserId, threshold, tier.getId());
            return;
        }

        String eventName = event.getName() == null || event.getName().isBlank() ? "your event" : event.getName();
        String tierName = tier.getName();
        String dashboardUrl = emailProps.getAppBaseUrl() + "/events/" + event.getId();

        // 1) In-app notification for the organizer user.
        Notification n = new Notification();
        n.setUserId(organizerUserId);
        n.setKind("sales.milestone." + threshold);
        n.setTitle(inAppTitle(threshold, tierName));
        n.setBody(eventName + " · " + tier.getSold() + "/" + tier.getQuantity()
                + " " + tierName + " sold (" + threshold + "%)");
        n.setLink("/events/" + event.getId());
        notifications.save(n);

        // 2) Email to the organization contact inbox (same recipient as refund alerts).
        Organization org = orgs.findById(event.getOrgId()).orElse(null);
        String to = org != null ? org.getContactEmail() : null;
        if (to == null || to.isBlank()) {
            log.warn("[milestone] no contact email for org {} — sent in-app only for tier {} {}%",
                    event.getOrgId(), tier.getId(), threshold);
            return;
        }

        // Recipient locale: the organizer user who created the event (null → EN).
        String locale = users.findById(organizerUserId).map(User::getLocale).orElse(null);

        Map<String, String> values = new LinkedHashMap<>();
        values.put("eventName", eventName);
        values.put("tierName", tierName);
        values.put("percentSold", String.valueOf(threshold));
        values.put("soldCount", String.valueOf(tier.getSold()));
        values.put("capacity", String.valueOf(tier.getQuantity()));
        values.put("dashboardUrl", dashboardUrl);

        EmailTemplateRenderer.Rendered r = renderer.render("sales-milestone-" + threshold, locale, values);
        email.send(to, emailSubject(threshold, tierName, locale), r.html(), r.text());
        log.info("[milestone] sent {}% notification for tier {} to {}", threshold, tier.getId(), to);
    }

    private static String inAppTitle(int t, String tier) {
        return switch (t) {
            case 50 -> tier + " is halfway sold out";
            case 80 -> tier + " is almost sold out";
            case 100 -> tier + " is sold out";
            default -> tier + " hit " + t + "% sold";
        };
    }

    private static String emailSubject(int t, String tier, String locale) {
        return switch (t) {
            case 50 -> EmailLocale.choose(locale,
                    tier + " is halfway sold out 🎉",
                    tier + " ya está a mitad de venta 🎉",
                    tier + " est à moitié vendu 🎉",
                    tier + " продано наполовину 🎉");
            case 80 -> EmailLocale.choose(locale,
                    "Only a few " + tier + " tickets left",
                    "Quedan pocas entradas de " + tier,
                    "Il ne reste que quelques billets " + tier,
                    "Залишилось лише кілька квитків " + tier);
            case 100 -> EmailLocale.choose(locale,
                    tier + " is SOLD OUT 🔥",
                    tier + " está AGOTADO 🔥",
                    tier + " est COMPLET 🔥",
                    tier + " РОЗПРОДАНО 🔥");
            default -> tier + " hit " + t + "% sold";
        };
    }
}

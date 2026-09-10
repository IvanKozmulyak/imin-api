package com.imin.iminapi.refund.email;

import com.imin.iminapi.util.LogSafe;
import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.refund.RefundRequest;
import com.imin.iminapi.refund.RefundRequestRepository;
import com.imin.iminapi.refund.event.RefundRequestRejectedEvent;
import com.imin.iminapi.refund.event.RefundRequestSubmittedEvent;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends the buyer/organizer/imin emails triggered by refund-request events.
 *
 * <p>On {@link RefundRequestSubmittedEvent}: three emails go out — buyer
 * acknowledgement, organizer notification, and the internal imin inbox.
 * On {@link RefundRequestRejectedEvent}: one email to the buyer with the
 * organizer's note.
 *
 * <p>Failures are logged + swallowed: the originating HTTP request has
 * already returned 2xx, and a retry loop here would not help. A separate
 * reconciler could resend stuck mail later.
 *
 * <p>Note on organizer routing: the plan calls for {@code Organization
 * .getOwnerUserId()} + {@code UserRepository} lookup, but {@code
 * Organization} in this codebase carries the destination directly as
 * {@code contactEmail} and there is no memberships table — so we route
 * to {@code contactEmail} and skip the indirection.
 */
@Component
public class RefundRequestEmailer {

    private static final Logger log = LoggerFactory.getLogger(RefundRequestEmailer.class);

    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties props;
    private final RefundRequestRepository requests;
    private final EventRepository events;
    private final OrderRepository orders;
    private final OrganizationRepository orgs;
    private final UserRepository users;

    public RefundRequestEmailer(EmailService email,
                                EmailTemplateRenderer renderer,
                                EmailProperties props,
                                RefundRequestRepository requests,
                                EventRepository events,
                                OrderRepository orders,
                                OrganizationRepository orgs,
                                UserRepository users) {
        this.email = email;
        this.renderer = renderer;
        this.props = props;
        this.requests = requests;
        this.events = events;
        this.orders = orders;
        this.orgs = orgs;
        this.users = users;
    }

    // AFTER_COMMIT + @Async, matching the sibling RefundConfirmationEmailer: a plain
    // @EventListener ran these Resend round-trips synchronously inside the submit/reject
    // database transaction, holding it open for the network call and mailing the buyer
    // about a request that a later rollback would have erased.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("ticketEmailExecutor")
    public void onSubmitted(RefundRequestSubmittedEvent ev) {
        RefundRequest rr = requests.findById(ev.requestId()).orElse(null);
        if (rr == null) {
            log.warn("[refund-request-email] request {} not found — skipping submit emails", ev.requestId());
            return;
        }

        String eventName = events.findById(rr.getEventId())
            .map(Event::getName).orElse("");
        String dashboardUrl = props.getAppBaseUrl()
            + "/events/" + rr.getEventId() + "/refund-requests/" + rr.getId();
        String phoneLine = (rr.getBuyerPhone() == null || rr.getBuyerPhone().isBlank())
            ? "" : " · " + rr.getBuyerPhone();

        Map<String, String> base = new LinkedHashMap<>();
        base.put("requestId", rr.getId().toString());
        base.put("orgId", rr.getOrgId().toString());
        base.put("eventName", eventName);
        base.put("buyerEmail", rr.getBuyerEmail());
        base.put("phoneLine", phoneLine);
        base.put("reason", rr.getReason().toWire());
        base.put("explanation", rr.getExplanation());
        base.put("dashboardUrl", dashboardUrl);

        // 1. Buyer ack — in the language they bought in (V78, snapshotted on the order).
        String buyerLocale = orders.findById(rr.getOrderId())
            .map(Order::getBuyerLocale)
            .orElse(null);
        EmailTemplateRenderer.Rendered buyer =
            renderer.render("refund-request-received-buyer", buyerLocale, base);
        String buyerSubject = EmailLocale.choose(buyerLocale,
            "We got your refund request · imin",
            "Hemos recibido tu solicitud de reembolso · imin",
            "Nous avons bien reçu votre demande de remboursement · imin",
            "Ми отримали ваш запит на повернення коштів · imin");
        safeSend(rr.getBuyerEmail(), buyerSubject, buyer);

        // 2. Organizer notify — route to the org's contactEmail. Locale comes from the
        // org's earliest-created user (its de-facto owner), NOT the buyer's: the two
        // sides of a refund request can be reading in different languages.
        String organizerEmail = orgs.findById(rr.getOrgId())
            .map(Organization::getContactEmail)
            .orElse(null);
        String organizerLocale = users.findByOrgIdOrderByCreatedAtAsc(rr.getOrgId())
            .stream().findFirst().map(User::getLocale).orElse(null);
        EmailTemplateRenderer.Rendered org =
            renderer.render("refund-request-notify-organizer", organizerLocale, base);
        String organizerSubject = EmailLocale.choose(organizerLocale,
            "New refund request · imin",
            "Nueva solicitud de reembolso · imin",
            "Nouvelle demande de remboursement · imin",
            "Новий запит на повернення коштів · imin");
        safeSend(organizerEmail, organizerSubject, org);

        // 3. Imin inbox. Deliberately English-only: the recipient is one internal ops
        // address, not a user, and rendering an operational alert in whichever language
        // the buyer happened to check out in would make the queue harder to work, not
        // easier. There is no locale to read for it either — it belongs to imin.
        EmailTemplateRenderer.Rendered imin = renderer.render("refund-request-notify-imin", base);
        safeSend(props.resolveRefundRequestInbox(), "[imin] new refund request", imin);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("ticketEmailExecutor")
    public void onRejected(RefundRequestRejectedEvent ev) {
        RefundRequest rr = requests.findById(ev.requestId()).orElse(null);
        if (rr == null) {
            log.warn("[refund-request-email] request {} not found — skipping reject email", ev.requestId());
            return;
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put("decisionNote", rr.getDecisionNote() == null ? "" : rr.getDecisionNote());

        // Buyer-facing, so the buyer's checkout language (V78) — the same source the
        // acknowledgement above already used. A rejection is the message they are most
        // likely to need to read carefully.
        String buyerLocale = orders.findById(rr.getOrderId())
            .map(Order::getBuyerLocale)
            .orElse(null);
        EmailTemplateRenderer.Rendered r = renderer.render("refund-request-rejected", buyerLocale, values);
        String subject = EmailLocale.choose(buyerLocale,
            "Your refund request · imin",
            "Tu solicitud de reembolso · imin",
            "Votre demande de remboursement · imin",
            "Ваш запит на повернення коштів · imin");
        safeSend(rr.getBuyerEmail(), subject, r);
    }

    private void safeSend(String to, String subject, EmailTemplateRenderer.Rendered r) {
        if (to == null || to.isBlank()) return;
        try {
            email.send(to, subject, r.html(), r.text());
        } catch (Exception e) {
            log.warn("[refund-request-email] send failed to={}: {}", LogSafe.email(to), LogSafe.redact(e.getMessage()));
        }
    }
}

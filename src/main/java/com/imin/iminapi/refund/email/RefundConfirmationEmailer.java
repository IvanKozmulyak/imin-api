package com.imin.iminapi.refund.email;

import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.refund.RefundTicketRepository;
import com.imin.iminapi.refund.event.RefundConfirmedEvent;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Listens for {@link RefundConfirmedEvent} after the refund webhook
 * transaction commits and dispatches the buyer's confirmation email
 * off-thread so the webhook ack is not blocked on Resend latency.
 *
 * <p>Failures are logged + swallowed: the webhook has already returned 200
 * to Stripe, so an email retry loop would not help. A follow-up reconciler
 * could resend stuck mails — Phase B.
 */
@Component
public class RefundConfirmationEmailer {

    private static final Logger log = LoggerFactory.getLogger(RefundConfirmationEmailer.class);

    private final RefundRepository refunds;
    private final RefundTicketRepository refundTickets;
    private final OrderRepository orders;
    private final EventRepository events;
    private final OrganizationRepository orgs;
    private final EmailService email;
    private final EmailTemplateRenderer renderer;

    public RefundConfirmationEmailer(RefundRepository refunds,
                                     RefundTicketRepository refundTickets,
                                     OrderRepository orders,
                                     EventRepository events,
                                     OrganizationRepository orgs,
                                     EmailService email,
                                     EmailTemplateRenderer renderer) {
        this.refunds = refunds;
        this.refundTickets = refundTickets;
        this.orders = orders;
        this.events = events;
        this.orgs = orgs;
        this.email = email;
        this.renderer = renderer;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("ticketEmailExecutor")
    public void onRefundConfirmed(RefundConfirmedEvent ev) {
        try {
            send(ev.refundId());
        } catch (Exception e) {
            log.warn("Refund confirmation email failed for refund {}: {}",
                ev.refundId(), e.getMessage(), e);
        }
    }

    void send(UUID refundId) {
        Refund refund = refunds.findById(refundId).orElse(null);
        if (refund == null) {
            log.warn("[refund-email] refund {} not found — skipping", refundId);
            return;
        }
        Order order = orders.findById(refund.getOrderId()).orElse(null);
        if (order == null || order.getEmail() == null || order.getEmail().isBlank()) {
            log.warn("[refund-email] order or buyer email missing for refund {}", refundId);
            return;
        }
        Event event = events.findById(order.getEventId()).orElse(null);
        Organization org = orgs.findById(order.getOrgId()).orElse(null);

        String eventName = event != null && event.getName() != null ? event.getName() : "your event";
        String organizerContact = org != null && org.getContactEmail() != null
            ? org.getContactEmail() : "support@imin.wtf";
        int ticketCount = refundTickets.findTicketIdsByRefundId(refundId).size();

        Map<String, String> values = new LinkedHashMap<>();
        values.put("eventName", eventName);
        values.put("orderShortCode", order.getId().toString().substring(0, 8));
        values.put("ticketCount", String.valueOf(ticketCount));
        values.put("amountFormatted", formatAmount(refund.getAmountMinor(), refund.getCurrency()));
        values.put("organizerContact", organizerContact);

        EmailTemplateRenderer.Rendered r = renderer.render("refund-confirmed", values);
        String subject = "Refund confirmed for " + eventName;
        email.send(order.getEmail(), subject, r.html(), r.text());
        log.info("[refund-email] sent for refund {} to {}", refundId, order.getEmail());
    }

    private static String formatAmount(long minor, String currency) {
        return String.format("%.2f %s", minor / 100.0, currency == null ? "" : currency.toUpperCase());
    }
}

package com.imin.iminapi.service.ticket;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Listens for {@link TicketsIssuedEvent} after the issuance transaction
 * commits and dispatches the buyer's confirmation email off-thread so the
 * Stripe webhook ack is not blocked on Resend latency.
 *
 * <p>Each ticket gets a hyperlinked QR image pointing at
 * {@code /api/v1/public/tickets/{token}/qr.png} — the same endpoint backing
 * the web ticket page, so we have one renderer and one cache story. When
 * Apple Wallet is configured, an "Add to Apple Wallet" link is appended
 * per ticket; otherwise the row is suppressed entirely so the email never
 * dangles a broken button in front of the buyer.
 */
@Component
public class TicketIssuanceEmailer {

    private static final Logger log = LoggerFactory.getLogger(TicketIssuanceEmailer.class);

    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final EventRepository events;
    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties emailProps;
    private final AppleWalletPassService wallet;

    public TicketIssuanceEmailer(OrderRepository orders,
                                  TicketRepository tickets,
                                  EventRepository events,
                                  EmailService email,
                                  EmailTemplateRenderer renderer,
                                  EmailProperties emailProps,
                                  AppleWalletPassService wallet) {
        this.orders = orders;
        this.tickets = tickets;
        this.events = events;
        this.email = email;
        this.renderer = renderer;
        this.emailProps = emailProps;
        this.wallet = wallet;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("ticketEmailExecutor")
    public void onTicketsIssued(TicketsIssuedEvent evt) {
        try {
            send(evt.orderId());
        } catch (Exception e) {
            // Buyer can self-recover via /recover; we log + swallow so a broken
            // mailer doesn't keep the webhook in a redelivery loop.
            log.warn("Ticket issuance email failed for order {}: {}",
                    evt.orderId(), e.getMessage(), e);
        }
    }

    void send(UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow();
        Event event = events.findById(order.getEventId()).orElseThrow();
        List<Ticket> issued = tickets.findByOrderIdOrderByCreatedAtAsc(order.getId());
        if (issued.isEmpty()) {
            log.warn("Order {} has no tickets — skipping email", orderId);
            return;
        }

        String base = baseUrl();
        String orderUrl = base + "/order/" + order.getToken();
        String recoverUrl = base + "/recover";
        String whenText = formatWhen(event);
        String whereText = formatWhere(event);
        String whereSep = (whenText.isEmpty() || whereText.isEmpty()) ? "" : " · ";

        boolean walletOn = wallet.isConfigured();
        String htmlBlocks = renderHtmlBlocks(issued, base, walletOn);
        String textBlocks = renderTextBlocks(issued, base, walletOn);

        // The renderer auto-escapes every value when emitting HTML. We need to
        // inject pre-built HTML for the per-ticket blocks, so render with a
        // placeholder and replace afterwards.
        Map<String, String> values = new LinkedHashMap<>();
        values.put("eventName", nullSafe(event.getName()));
        values.put("eventWhen", whenText);
        values.put("eventWhere", whereText);
        values.put("eventWhereSeparator", whereSep);
        values.put("ticketBlocks", "__TICKETS_BLOCK_PLACEHOLDER__");
        values.put("orderUrl", orderUrl);
        values.put("recoverUrl", recoverUrl);

        EmailTemplateRenderer.Rendered r = renderer.render("ticket-issued", values);
        String html = r.html().replace("__TICKETS_BLOCK_PLACEHOLDER__", htmlBlocks);
        String text = r.text().replace("__TICKETS_BLOCK_PLACEHOLDER__", textBlocks);

        String subject = issued.size() == 1
                ? "Your ticket for " + nullSafe(event.getName())
                : "Your tickets for " + nullSafe(event.getName());

        email.send(order.getEmail(), subject, html, text);
        log.info("Sent issuance email for order {} ({} ticket(s)) to {}",
                order.getId(), issued.size(), order.getEmail());
    }

    private String renderHtmlBlocks(List<Ticket> issued, String base, boolean walletOn) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < issued.size(); i++) {
            Ticket t = issued.get(i);
            String qrUrl = base + "/api/v1/public/tickets/" + t.getToken() + "/qr.png";
            String ticketUrl = base + "/tickets/" + t.getToken();
            String walletUrl = base + "/api/v1/public/tickets/" + t.getToken() + "/apple-wallet.pkpass";
            String label = "Ticket " + (i + 1) + " of " + issued.size() + " — " + htmlEscape(t.getTierName());

            b.append("<div style=\"margin: 0 0 28px;\">")
                    .append("<div style=\"font-weight: 600; margin-bottom: 8px;\">").append(label).append("</div>")
                    .append("<img src=\"").append(qrUrl).append("\" alt=\"QR\" ")
                    .append("style=\"display:block; width: 240px; height: 240px; border: 1px solid #e5e5e5; padding: 12px; background: #fff;\"/>")
                    .append("<p style=\"margin: 12px 0 4px;\"><a href=\"").append(ticketUrl)
                    .append("\" style=\"color: #0a66c2; text-decoration: none;\">Open this ticket →</a></p>");
            if (walletOn) {
                b.append("<p style=\"margin: 4px 0;\"><a href=\"").append(walletUrl)
                        .append("\" style=\"color: #0a66c2; text-decoration: none;\">Add to Apple Wallet →</a></p>");
            }
            b.append("</div>");
        }
        return b.toString();
    }

    private String renderTextBlocks(List<Ticket> issued, String base, boolean walletOn) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < issued.size(); i++) {
            Ticket t = issued.get(i);
            String ticketUrl = base + "/tickets/" + t.getToken();
            String walletUrl = base + "/api/v1/public/tickets/" + t.getToken() + "/apple-wallet.pkpass";
            b.append("Ticket ").append(i + 1).append(" of ").append(issued.size())
                    .append(" — ").append(nullSafe(t.getTierName())).append('\n');
            b.append("  Open: ").append(ticketUrl).append('\n');
            if (walletOn) {
                b.append("  Apple Wallet: ").append(walletUrl).append('\n');
            }
            b.append('\n');
        }
        return b.toString();
    }

    private String baseUrl() {
        String base = emailProps.getAppBaseUrl();
        if (base != null && base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base == null ? "" : base;
    }

    private String formatWhen(Event event) {
        if (event.getStartsAt() == null) return "";
        ZoneId zone = event.getTimezone() == null
                ? ZoneId.systemDefault()
                : ZoneId.of(event.getTimezone());
        return DateTimeFormatter.ofPattern("EEEE, d LLL yyyy · HH:mm")
                .withZone(zone)
                .format(event.getStartsAt());
    }

    private String formatWhere(Event event) {
        String name = event.getVenueName();
        String city = event.getVenueCity();
        if (name != null && !name.isBlank() && city != null && !city.isBlank()) return name + ", " + city;
        if (name != null && !name.isBlank()) return name;
        if (city != null && !city.isBlank()) return city;
        return "";
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

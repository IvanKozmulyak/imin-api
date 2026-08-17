package com.imin.iminapi.service.ticket;

import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.dto.publicapi.TicketWallets;
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
 * the web ticket page, so we have one renderer and one cache story. Beneath it
 * go the wallet links {@link WalletOffers} says are actually available for that
 * ticket; a wallet that is off, or a ticket that is no longer live, contributes
 * no row at all, so the email never dangles a broken button in front of a buyer.
 *
 * <p><b>Both wallets are offered to every recipient, and that is correct.</b> An
 * email has no user agent and is read on more devices than it was sent from —
 * the buyer who checked out on a laptop opens it on a phone we cannot name. So
 * an iPhone owner may see a Google link. The alternative is guessing, and
 * guessing wrong hides the only working button.
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
    private final TicketProperties ticketProps;
    private final WalletOffers wallet;

    public TicketIssuanceEmailer(OrderRepository orders,
                                  TicketRepository tickets,
                                  EventRepository events,
                                  EmailService email,
                                  EmailTemplateRenderer renderer,
                                  EmailProperties emailProps,
                                  TicketProperties ticketProps,
                                  WalletOffers wallet) {
        this.orders = orders;
        this.tickets = tickets;
        this.events = events;
        this.email = email;
        this.renderer = renderer;
        this.emailProps = emailProps;
        this.ticketProps = ticketProps;
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

    /**
     * Renders and sends the ticket email for one order.
     *
     * <p>Public so {@code BuyerOrderActionsController} can re-send on demand
     * (spec §4.6). There is exactly one renderer for this mail; a second one for
     * "resend" would drift from the original the first time a template changed.
     */
    public void send(UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow();
        Event event = events.findById(order.getEventId()).orElseThrow();
        List<Ticket> issued = tickets.findByOrderIdOrderByCreatedAtAsc(order.getId());
        if (issued.isEmpty()) {
            log.warn("Order {} has no tickets — skipping email", orderId);
            return;
        }

        String siteBase = buyerSiteBase();
        String apiBase = apiBase();
        String orderUrl = siteBase + "/order/" + order.getToken();
        String recoverUrl = siteBase + "/recover";
        String whenText = formatWhen(event);
        String whereText = formatWhere(event);
        String whereSep = (whenText.isEmpty() || whereText.isEmpty()) ? "" : " · ";

        String htmlBlocks = renderHtmlBlocks(issued, siteBase, apiBase);
        String textBlocks = renderTextBlocks(issued, siteBase, apiBase);

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

        // The buyer's language, snapshotted at checkout (V78). Null ⇒ English, which is
        // exactly what the renderer does with a null locale.
        String locale = order.getBuyerLocale();
        EmailTemplateRenderer.Rendered r = renderer.render("ticket-issued", locale, values);
        String html = r.html().replace("__TICKETS_BLOCK_PLACEHOLDER__", htmlBlocks);
        String text = r.text().replace("__TICKETS_BLOCK_PLACEHOLDER__", textBlocks);

        String name = nullSafe(event.getName());
        String subject = issued.size() == 1
                ? EmailLocale.choose(locale,
                        "Your ticket for " + name,
                        "Tu entrada para " + name,
                        "Votre billet pour " + name,
                        "Ваш квиток на " + name)
                : EmailLocale.choose(locale,
                        "Your tickets for " + name,
                        "Tus entradas para " + name,
                        "Vos billets pour " + name,
                        "Ваші квитки на " + name);

        email.send(order.getEmail(), subject, html, text);
        log.info("Sent issuance email for order {} ({} ticket(s)) to {}",
                order.getId(), issued.size(), order.getEmail());
    }

    private String renderHtmlBlocks(List<Ticket> issued, String siteBase, String apiBase) {
        StringBuilder b = new StringBuilder();
        int total = issued.size();
        for (int i = 0; i < total; i++) {
            Ticket t = issued.get(i);
            String qrUrl = apiBase + "/api/v1/public/tickets/" + t.getToken() + "/qr.png";
            String ticketUrl = siteBase + "/tickets/" + t.getToken();
            TicketWallets wallets = wallet.forTicket(t);
            String eyebrow = "TICKET " + (i + 1) + " OF " + total;
            String tierName = htmlEscape(t.getTierName());

            // Card matches the template style: lavender tile, mono eyebrow,
            // brand-blue CTA links. The QR sits on a white inner tile so the
            // scanner has a high-contrast quiet zone regardless of mail-client
            // background tinting.
            b.append("<div style=\"margin: 0 0 16px; padding: 18px; background: #f4f2fa; border: 1px solid #d4cee6; border-radius: 8px;\">")
                    .append("<div style=\"font-family: 'Space Mono', ui-monospace, Menlo, Consolas, monospace; font-size: 11px; font-weight: 700; letter-spacing: 1.5px; text-transform: uppercase; color: #7a738f;\">")
                    .append(eyebrow).append("</div>")
                    .append("<div style=\"margin-top: 4px; font-size: 15px; font-weight: 600; color: #11091f; letter-spacing: -0.2px;\">")
                    .append(tierName).append("</div>")
                    .append("<div style=\"margin-top: 14px; display: inline-block; padding: 12px; background: #ffffff; border: 1px solid #d4cee6; border-radius: 6px;\">")
                    .append("<img src=\"").append(qrUrl).append("\" alt=\"QR\" width=\"240\" height=\"240\" ")
                    .append("style=\"display: block; width: 240px; height: 240px;\"/>")
                    .append("</div>")
                    .append("<p style=\"margin: 14px 0 0; font-size: 13px; line-height: 1.5;\"><a href=\"").append(ticketUrl)
                    .append("\" style=\"color: #2d5cff; text-decoration: none; font-weight: 600;\">Open this ticket &rarr;</a></p>");
            if (wallets.apple().available()) {
                b.append(walletLinkHtml(wallets.apple().url(), "Add to Apple Wallet"));
            }
            if (wallets.google().available()) {
                b.append(walletLinkHtml(wallets.google().url(), "Save to Google Wallet"));
            }
            b.append("</div>");
        }
        return b.toString();
    }

    private String renderTextBlocks(List<Ticket> issued, String siteBase, String apiBase) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < issued.size(); i++) {
            Ticket t = issued.get(i);
            String ticketUrl = siteBase + "/tickets/" + t.getToken();
            TicketWallets wallets = wallet.forTicket(t);
            b.append("Ticket ").append(i + 1).append(" of ").append(issued.size())
                    .append(" — ").append(nullSafe(t.getTierName())).append('\n');
            b.append("  Open: ").append(ticketUrl).append('\n');
            if (wallets.apple().available()) {
                b.append("  Apple Wallet: ").append(wallets.apple().url()).append('\n');
            }
            if (wallets.google().available()) {
                b.append("  Google Wallet: ").append(wallets.google().url()).append('\n');
            }
            b.append('\n');
        }
        return b.toString();
    }

    /**
     * One wallet row. The URL is not escaped and does not need to be: it is
     * built by {@link WalletOffers} from a configured base and a base64url
     * token, so it carries no {@code "} and no {@code <} — but it is also never
     * user input, which is the reason that stays true.
     */
    private static String walletLinkHtml(String url, String label) {
        return "<p style=\"margin: 4px 0 0; font-size: 13px; line-height: 1.5;\"><a href=\""
                + url + "\" style=\"color: #2d5cff; text-decoration: none; font-weight: 600;\">"
                + label + " &rarr;</a></p>";
    }

    private String buyerSiteBase() {
        return trimTrailingSlash(emailProps.getBuyerSiteBaseUrl());
    }

    private String apiBase() {
        return trimTrailingSlash(ticketProps.getApiPublicBaseUrl());
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
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

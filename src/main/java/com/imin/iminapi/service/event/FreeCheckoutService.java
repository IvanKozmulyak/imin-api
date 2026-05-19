package com.imin.iminapi.service.event;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Issues tickets for free orders (totalMinor == 0). Stripe is bypassed entirely:
 * inventory is reserved + confirmed in the same transaction, an Order row is
 * written, N Ticket rows are written, and the buyer is redirected to
 * {@code /order/{token}} on the public site where they can view and download
 * their tickets. A confirmation email with the same link is dispatched
 * best-effort after the transaction commits.
 *
 * <p>Eligibility (event publicly visible, tier on sale, etc.) is the caller's
 * responsibility — {@link StripeCheckoutService} runs the same checks before
 * delegating here.
 */
@Service
public class FreeCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(FreeCheckoutService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final InventoryService inventory;
    private final EmailService email;
    private final EmailProperties emailProps;

    public FreeCheckoutService(OrderRepository orders,
                                TicketRepository tickets,
                                InventoryService inventory,
                                EmailService email,
                                EmailProperties emailProps) {
        this.orders = orders;
        this.tickets = tickets;
        this.inventory = inventory;
        this.email = email;
        this.emailProps = emailProps;
    }

    /**
     * Atomically reserves + confirms inventory, creates one Order and N Tickets,
     * and returns the public order URL the buyer should be redirected to. Email
     * delivery is fired AFTER this method returns (caller-side); see
     * {@link #sendConfirmation(Order, Event, List)}.
     */
    @Transactional
    public Order issueFreeOrder(Event event, TicketTier tier, int quantity, String buyerEmail) {
        inventory.reserve(tier.getId(), quantity);
        inventory.confirmSold(tier.getId(), quantity);

        Order order = new Order();
        order.setToken(randomToken());
        order.setEventId(event.getId());
        order.setOrgId(event.getOrgId());
        order.setEmail(buyerEmail.trim().toLowerCase());
        order.setTotalMinor(0L);
        order.setCurrency(event.getCurrency());
        order.setPaymentMethod("free");
        orders.save(order);

        for (int i = 0; i < quantity; i++) {
            Ticket t = new Ticket();
            t.setToken(randomToken());
            t.setOrderId(order.getId());
            t.setEventId(event.getId());
            t.setTierId(tier.getId());
            t.setTierName(tier.getName());
            tickets.save(t);
        }
        return order;
    }

    /**
     * Build the public-site URL the BE returns to the buyer (and embeds in the
     * confirmation email). The host is taken from {@code imin.email.app-base-url}
     * which already defaults to the public site for password-reset links.
     */
    public String orderUrl(Order order) {
        String base = emailProps.getAppBaseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/order/" + order.getToken();
    }

    /**
     * Fire-and-log confirmation email. Any failure is logged but not propagated —
     * the buyer already has the redirect URL in hand.
     */
    public void sendConfirmation(Order order, Event event, List<Ticket> issued) {
        try {
            String url = orderUrl(order);
            String ticketCount = issued.size() + (issued.size() == 1 ? " ticket" : " tickets");
            String subject = "Your " + event.getName() + " " + ticketCount;
            StringBuilder text = new StringBuilder();
            text.append("Hi,\n\n");
            text.append("You're in for ").append(event.getName()).append(".\n\n");
            text.append("Open your order: ").append(url).append("\n\n");
            text.append("Each ticket can also be viewed directly:\n");
            String base = emailProps.getAppBaseUrl();
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            for (Ticket t : issued) {
                text.append("- ").append(base).append("/tickets/").append(t.getToken()).append("\n");
            }
            text.append("\nSee you there,\nimin\n");
            String html = "<p>You're in for <strong>" + escape(event.getName()) + "</strong>.</p>"
                    + "<p><a href=\"" + url + "\">Open your order</a></p>";
            email.send(order.getEmail(), subject, html, text.toString());
        } catch (Exception e) {
            log.warn("Free-ticket confirmation email failed for order {}: {}",
                    order.getId(), e.getMessage());
        }
    }

    /** Convenience used by callers + the controller path. */
    public List<Ticket> findOrderTickets(UUID orderId) {
        return tickets.findByOrderIdOrderByCreatedAtAsc(orderId);
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

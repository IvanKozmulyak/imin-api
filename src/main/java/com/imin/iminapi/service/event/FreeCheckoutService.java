package com.imin.iminapi.service.event;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.model.CheckoutAttribution;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.PromoCode;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final PromoCodeRepository promos;
    private final InventoryService inventory;
    private final EmailService email;
    private final EmailProperties emailProps;
    private final Clock clock;
    private final org.springframework.context.ApplicationEventPublisher publisher;

    public FreeCheckoutService(OrderRepository orders,
                                TicketRepository tickets,
                                PromoCodeRepository promos,
                                InventoryService inventory,
                                EmailService email,
                                EmailProperties emailProps,
                                Clock clock,
                                org.springframework.context.ApplicationEventPublisher publisher) {
        this.orders = orders;
        this.tickets = tickets;
        this.promos = promos;
        this.inventory = inventory;
        this.email = email;
        this.emailProps = emailProps;
        this.clock = clock;
        this.publisher = publisher;
    }

    /**
     * Atomically reserves + confirms inventory, creates one Order and N Tickets,
     * and returns the public order URL the buyer should be redirected to. Email
     * delivery is fired AFTER this method returns (caller-side); see
     * {@link #sendConfirmation(Order, Event, List)}.
     *
     * @param appliedPromo the promo whose discount zeroed the total. Pass {@code null}
     *                     when the tier itself was already free. When non-null, the
     *                     promo's {@code used_count} is incremented atomically in the
     *                     same transaction so usage caps are enforced consistently
     *                     between the paid (webhook-driven) and free (inline) paths.
     * @param adsConsent   the buyer's cookie-consent-derived ads-consent decision (§7).
     *                     Snapshotted onto {@code orders.ads_consent}; the Meta CAPI
     *                     outbox writer only emits a server-side event when it is true.
     * @param attribution  the last-touch utm_* + anon_id the browser landed with (V62).
     *                     Snapshotted onto {@code orders.utm_*}; the free path stamps it
     *                     inline because it never round-trips through Stripe metadata.
     *                     Never null — pass {@link CheckoutAttribution#NONE} for none.
     */
    @Transactional
    public Order issueFreeOrder(Event event, TicketTier tier, int quantity,
                                 String buyerEmail, PromoCode appliedPromo, boolean adsConsent,
                                 boolean marketingOptIn, CheckoutAttribution attribution) {
        // Reserve + confirm atomically in the same transaction. expires_at is a
        // short fallback that the sweeper would only see if the surrounding
        // transaction crashed between reserve() and confirmSold() — both calls
        // run on the same connection so that can't happen, but we still set a
        // sane value rather than null.
        Instant expiresAt = clock.instant().plus(Duration.ofMinutes(5));
        UUID reservationId = inventory.reserve(tier.getId(), quantity, expiresAt, null);
        inventory.confirmSold(reservationId);

        Order order = new Order();
        order.setToken(randomToken());
        order.setEventId(event.getId());
        order.setOrgId(event.getOrgId());
        order.setEmail(buyerEmail.trim().toLowerCase());
        order.setTotalMinor(0L);
        order.setCurrency(event.getCurrency());
        order.setPaymentMethod("free");
        order.setAdsConsent(adsConsent);
        order.setMarketingOptIn(marketingOptIn);
        (attribution == null ? CheckoutAttribution.NONE : attribution).applyTo(order);
        if (appliedPromo != null) {
            order.setPromoCodeId(appliedPromo.getId());
        }
        orders.save(order);

        // Increment promo usage inline. The paid path does this on the
        // checkout.session.completed webhook; here we have to fold it into the
        // same transaction so a free-checkout race can't over-redeem.
        if (appliedPromo != null) {
            promos.incrementUsedCount(appliedPromo.getId());
        }

        for (int i = 0; i < quantity; i++) {
            Ticket t = new Ticket();
            t.setToken(randomToken());
            t.setOrderId(order.getId());
            t.setEventId(event.getId());
            t.setTierId(tier.getId());
            t.setTierName(tier.getName());
            tickets.save(t);
        }
        // Same post-issuance event as the paid path (PaidCheckoutService). AFTER_COMMIT
        // listeners deliver the branded ticket-issued email (TicketIssuanceEmailer),
        // audience membership projection, sales-milestone notifications, and the
        // predictor reforecast trigger — the free path must not silently skip them.
        publisher.publishEvent(new com.imin.iminapi.service.ticket.TicketsIssuedEvent(order.getId()));
        return order;
    }

    /**
     * Build the public-site URL the BE returns to the buyer (and embeds in the
     * confirmation email). Host comes from {@code imin.email.buyer-site-base-url}
     * (the buyer site, e.g. https://app.imin.wtf) — distinct from
     * {@code imin.email.app-base-url} which is the organizer dashboard.
     */
    public String orderUrl(Order order) {
        String base = emailProps.getBuyerSiteBaseUrl();
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
            String base = emailProps.getBuyerSiteBaseUrl();
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

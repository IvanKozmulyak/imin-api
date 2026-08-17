package com.imin.iminapi.service.event;

import com.imin.iminapi.email.EmailLocale;
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
     * @param buyerLocale  the buyer's UI language (V78), snapshotted onto
     *                     {@code orders.buyer_locale} so their emails come back in it.
     *                     Null ⇒ no preference ⇒ English. Re-normalized here so a caller
     *                     that skipped normalization can't write junk into the column.
     */
    @Transactional
    public Order issueFreeOrder(Event event, TicketTier tier, int quantity,
                                 String buyerEmail, PromoCode appliedPromo, boolean adsConsent,
                                 boolean marketingOptIn, CheckoutAttribution attribution,
                                 String buyerLocale) {
        return issueFreeOrder(event, tier, quantity, buyerEmail, appliedPromo, adsConsent,
                marketingOptIn, attribution, buyerLocale, null);
    }

    /**
     * @param idempotencyKey the caller's {@code Idempotency-Key} (already trimmed by
     *                       {@code IdempotencyKey.normalize}), or null when they sent none.
     *
     * <h2>Why the INSERT is the claim, rather than a separate claim step</h2>
     *
     * <p>The native paid path claims its key on the reservation <i>before</i> it calls
     * Stripe, because a blocking HTTP call sits between the hold and the answer and
     * cannot be rolled back. Nothing sits between here: reserve, confirm, order and
     * tickets are one transaction, and the answer a replay must return is the Order
     * itself. So {@code uq_orders_idem} on the row we are already writing is the whole
     * mechanism — and it is strictly stronger than a claim step, because a loser's
     * seats go back by transaction rollback rather than by a compensating write that
     * could itself fail.
     *
     * <p><b>There is deliberately no explicit flush.</b> {@code Order} uses
     * {@code GenerationType.UUID}, so Hibernate defers the INSERT to commit and the
     * constraint fires there rather than at {@code save()}. That was originally
     * assumed to be a problem worth flushing away; it is not. Spring's
     * {@code HibernateJpaDialect} translates a commit-time
     * {@code ConstraintViolationException} into the same
     * {@code DataIntegrityViolationException} a flush would have produced, so the
     * caller's race handling sees exactly the same exception either way — verified by
     * deleting the flush and watching every test stay green, which is the only reason
     * it is not here.
     */
    @Transactional
    public Order issueFreeOrder(Event event, TicketTier tier, int quantity,
                                 String buyerEmail, PromoCode appliedPromo, boolean adsConsent,
                                 boolean marketingOptIn, CheckoutAttribution attribution,
                                 String buyerLocale, String idempotencyKey) {
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
        // EmailNormalizer, not an inline trim().toLowerCase(): the address is half
        // of the idempotency key's namespace, so the value stored here and the value
        // findByIdempotencyKey looks up with must come from the same function. (It is
        // also the Locale.ROOT one — a default-locale toLowerCase() maps 'I' to 'ı'
        // under a Turkish JVM default and would silently change stored addresses.)
        order.setEmail(normalizeEmail(buyerEmail));
        order.setTotalMinor(0L);
        order.setCurrency(event.getCurrency());
        order.setPaymentMethod("free");
        order.setAdsConsent(adsConsent);
        order.setMarketingOptIn(marketingOptIn);
        order.setBuyerLocale(EmailLocale.normalizeOrNull(buyerLocale));
        (attribution == null ? CheckoutAttribution.NONE : attribution).applyTo(order);
        if (appliedPromo != null) {
            order.setPromoCodeId(appliedPromo.getId());
        }
        order.setIdempotencyKey(idempotencyKey);
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

    /**
     * The order a previous free checkout with this {@code Idempotency-Key} already
     * produced for this buyer on this event, or empty when there is none.
     *
     * <p>Used twice by {@code StripeCheckoutService}: once optimistically, before the
     * request is priced, so a replay never re-prices and never fails because the tier
     * sold out between the buyer's two taps; and once after {@code uq_orders_idem}
     * rejects a concurrent duplicate, to resolve the race to the winning row.
     *
     * <p>Empty for a null/blank key or a null/blank email rather than matching
     * broadly — a NULL key column matches nothing under the index either, so
     * answering "here is some order" for an absent key would hand back a row the
     * constraint never made unique.
     */
    public java.util.Optional<Order> findByIdempotencyKey(UUID eventId, String buyerEmail, String idempotencyKey) {
        if (eventId == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return java.util.Optional.empty();
        }
        String email = normalizeEmail(buyerEmail);
        if (email == null || email.isEmpty()) return java.util.Optional.empty();
        return orders.findByEventIdAndEmailAndIdempotencyKey(eventId, email, idempotencyKey);
    }

    /**
     * The one function that decides what {@code orders.email} holds on this path.
     * The write and the idempotency lookup both go through it, so they cannot
     * disagree about which row a key belongs to. Same normalization
     * {@code Order.onWrite} uses to derive {@code email_normalized} (V86).
     */
    private static String normalizeEmail(String raw) {
        return com.imin.iminapi.audience.service.EmailNormalizer.normalize(raw);
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

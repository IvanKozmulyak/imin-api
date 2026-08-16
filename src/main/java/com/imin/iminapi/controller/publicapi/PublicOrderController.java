package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.dto.publicapi.PublicOrderResponse;
import com.imin.iminapi.dto.publicapi.PublicTicketResponse;
import com.imin.iminapi.dto.publicapi.SmsConsentRequest;
import com.imin.iminapi.dto.publicapi.SmsConsentResponse;
import com.imin.iminapi.service.audience.SmsConsentService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketState;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.service.event.PublicEventService;
import com.imin.iminapi.service.ticket.QrPayloadSigner;
import com.imin.iminapi.service.ticket.TicketProperties;
import com.imin.iminapi.service.ticket.WalletOffers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Public, unauthenticated lookup of an order or a single ticket by URL-safe token.
 *
 * <p>Tokens are 24 random bytes (~32 chars base64url) generated at issue time and
 * stored as the only public lookup key — UUIDs are never exposed. A wrong/forged
 * token returns the standard leak-safe 404 NOT_FOUND envelope (same as the event
 * detail endpoint).
 *
 * <p>{@code no-store} on the response: orders and tickets are buyer-specific; we
 * don't want a shared CDN cache hit serving one buyer's order to another.
 */
@RestController
public class PublicOrderController {

    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final EventRepository events;
    private final QrPayloadSigner qrSigner;
    private final WalletOffers wallet;
    private final TicketProperties ticketProps;
    private final SmsConsentService smsConsentService;
    private final PublicEventService publicEventService;

    public PublicOrderController(OrderRepository orders,
                                  TicketRepository tickets,
                                  EventRepository events,
                                  QrPayloadSigner qrSigner,
                                  WalletOffers wallet,
                                  TicketProperties ticketProps,
                                  SmsConsentService smsConsentService,
                                  PublicEventService publicEventService) {
        this.orders = orders;
        this.tickets = tickets;
        this.events = events;
        this.qrSigner = qrSigner;
        this.wallet = wallet;
        this.ticketProps = ticketProps;
        this.smsConsentService = smsConsentService;
        this.publicEventService = publicEventService;
    }

    @GetMapping("/api/v1/public/orders/{token}")
    @Transactional(readOnly = true)
    public ResponseEntity<PublicOrderResponse> getOrder(@PathVariable String token) {
        Order order = orders.findByToken(token).orElseThrow(() -> ApiException.notFound("Order"));
        Event event = events.findById(order.getEventId())
                .orElseThrow(() -> ApiException.notFound("Order"));
        List<Ticket> ticketRows = tickets.findByOrderIdOrderByCreatedAtAsc(order.getId());

        var body = new PublicOrderResponse(
                order.getToken(),
                order.getEmail(),
                order.getTotalMinor(),
                order.getCurrency(),
                order.getPaymentMethod(),
                order.getCreatedAt(),
                eventBlock(event),
                ticketRows.stream()
                        .map(t -> new PublicOrderResponse.Ticket(
                                t.getToken(), t.getTierName(), normalizeState(t.getState()),
                                qrSigner.sign(t.getToken()),
                                // Per ticket, not per order: a refunded ticket
                                // among live siblings is its own answer.
                                wallet.forTicket(t)))
                        .toList(),
                receiptLines(ticketRows),
                subtotalOf(ticketRows),
                discountOf(order, ticketRows),
                order.getApplicationFeeMinor());

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }

    @GetMapping("/api/v1/public/tickets/{token}")
    @Transactional(readOnly = true)
    public ResponseEntity<PublicTicketResponse> getTicket(@PathVariable String token) {
        Ticket ticket = tickets.findByToken(token).orElseThrow(() -> ApiException.notFound("Ticket"));
        Event event = events.findById(ticket.getEventId())
                .orElseThrow(() -> ApiException.notFound("Ticket"));
        Order order = orders.findById(ticket.getOrderId())
                .orElseThrow(() -> ApiException.notFound("Ticket"));

        String qrPayload = qrSigner.sign(ticket.getToken());
        String base = baseUrl();
        String qrUrl = base + "/api/v1/public/tickets/" + ticket.getToken() + "/qr.png";
        var wallets = wallet.forTicket(ticket);

        var body = new PublicTicketResponse(
                ticket.getToken(),
                normalizeState(ticket.getState()),
                ticket.getTierName(),
                qrPayload,
                qrUrl,
                // The deprecated flag is READ OFF the new block rather than
                // computed beside it, so the two cannot drift apart even by
                // accident. Its old value was the Apple config alone, which
                // claimed "available" for a refunded ticket the pkpass endpoint
                // answers 409 for — a narrowing, and one that can only ever
                // remove a button that was going to fail.
                wallets.apple().available(),
                wallets,
                new PublicTicketResponse.Event(
                        event.getId(), event.getName(), event.getSlug(),
                        event.getStartsAt(), event.getEndsAt(), event.getTimezone(),
                        event.getVenueName(), event.getVenueStreet(), event.getVenueCity(),
                        event.getVenuePostalCode(), event.getVenueCountry(),
                        event.getPosterUrl()),
                new PublicTicketResponse.Order(order.getToken(), order.getEmail()));

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }

    /**
     * Post-purchase SMS marketing opt-in from the order-confirmation page (§4).
     * Keyed by the order token. Explicit-only consent (§7); a ticked checkbox with
     * a valid phone records a channel='sms' proof row. Unknown token → leak-safe 404.
     */
    @PostMapping("/api/v1/public/orders/{token}/sms-consent")
    public ResponseEntity<SmsConsentResponse> smsConsent(@PathVariable String token,
                                                         @RequestBody(required = false) SmsConsentRequest body) {
        SmsConsentResponse response = smsConsentService.submit(token, body);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(response);
    }

    private PublicOrderResponse.Event eventBlock(Event event) {
        return new PublicOrderResponse.Event(
                event.getId(), event.getName(), event.getSlug(),
                event.getStartsAt(), event.getEndsAt(), event.getTimezone(),
                event.getVenueName(), event.getVenueStreet(), event.getVenueCity(),
                event.getVenuePostalCode(), event.getVenueCountry(),
                event.getPosterUrl(),
                publicEventService.resolveMetaPixelId(event.getOrgId(), event.getId()));
    }

    /**
     * Normalizes the raw {@code state} column to the canonical wire value.
     * Legacy free-flow rows persisted before V26 carry {@code 'pre'}; we
     * surface those as {@code 'issued'} so the FE only has to handle one
     * vocabulary.
     */
    /**
     * The receipt rows, grouped by {@code (tierId, priceMinor)} and ordered by
     * first appearance so the receipt reads in the order the tickets were
     * issued. Every ticket counts, including refunded ones — the receipt says
     * what was charged, and hiding a refunded ticket would make the arithmetic
     * disagree with the total.
     */
    private static List<PublicOrderResponse.Line> receiptLines(List<Ticket> ticketRows) {
        record Key(UUID tierId, long priceMinor) {}
        Map<Key, List<Ticket>> grouped = ticketRows.stream()
                .collect(Collectors.groupingBy(t -> new Key(t.getTierId(), t.getPriceMinor()),
                        LinkedHashMap::new, Collectors.toList()));

        List<PublicOrderResponse.Line> out = new ArrayList<>();
        grouped.forEach((key, rows) -> out.add(new PublicOrderResponse.Line(
                rows.get(0).getTierName(),
                rows.size(),
                key.priceMinor(),
                key.priceMinor() * rows.size())));
        return out;
    }

    private static long subtotalOf(List<Ticket> ticketRows) {
        return ticketRows.stream().mapToLong(Ticket::getPriceMinor).sum();
    }

    /**
     * The promo discount, or zero.
     *
     * <p>Deliberately <b>not</b> derived from {@code subtotal + fee - total}
     * alone. {@code PaidCheckoutService} snapshots each ticket's price from the
     * tier at webhook time, not at quote time, so an organizer who moved a price
     * between session creation and fulfilment leaves a nonzero residue on an
     * order that carried no promo — and a "Discount" line would appear on a
     * receipt for a discount nobody gave. The residue is only reported when the
     * order actually carries a promo code.
     */
    private static long discountOf(Order order, List<Ticket> ticketRows) {
        if (order.getPromoCodeId() == null) return 0L;
        long residue = subtotalOf(ticketRows) + order.getApplicationFeeMinor() - order.getTotalMinor();
        return Math.max(0L, residue);
    }

    private static String normalizeState(String wire) {
        return TicketState.fromWire(wire).wire();
    }

    private String baseUrl() {
        // API's own public URL — the QR endpoint lives on this server, not on the
        // buyer-facing site. So the absolute URL we hand the FE / email must point here.
        String base = ticketProps.getApiPublicBaseUrl();
        if (base != null && base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base == null ? "" : base;
    }
}

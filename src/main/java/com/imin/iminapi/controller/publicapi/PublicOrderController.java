package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.dto.publicapi.PublicOrderResponse;
import com.imin.iminapi.dto.publicapi.PublicTicketResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketState;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.service.ticket.AppleWalletPassService;
import com.imin.iminapi.service.ticket.QrPayloadSigner;
import com.imin.iminapi.service.ticket.TicketProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    private final AppleWalletPassService wallet;
    private final TicketProperties ticketProps;

    public PublicOrderController(OrderRepository orders,
                                  TicketRepository tickets,
                                  EventRepository events,
                                  QrPayloadSigner qrSigner,
                                  AppleWalletPassService wallet,
                                  TicketProperties ticketProps) {
        this.orders = orders;
        this.tickets = tickets;
        this.events = events;
        this.qrSigner = qrSigner;
        this.wallet = wallet;
        this.ticketProps = ticketProps;
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
                                t.getToken(), t.getTierName(), normalizeState(t.getState())))
                        .toList());

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

        var body = new PublicTicketResponse(
                ticket.getToken(),
                normalizeState(ticket.getState()),
                ticket.getTierName(),
                qrPayload,
                qrUrl,
                wallet.isConfigured(),
                new PublicTicketResponse.Event(
                        event.getName(), event.getSlug(),
                        event.getStartsAt(), event.getTimezone(),
                        event.getVenueName(), event.getVenueCity()),
                new PublicTicketResponse.Order(order.getToken(), order.getEmail()));

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }

    private static PublicOrderResponse.Event eventBlock(Event event) {
        return new PublicOrderResponse.Event(
                event.getName(), event.getSlug(),
                event.getStartsAt(), event.getTimezone(),
                event.getVenueName(), event.getVenueCity());
    }

    /**
     * Normalizes the raw {@code state} column to the canonical wire value.
     * Legacy free-flow rows persisted before V26 carry {@code 'pre'}; we
     * surface those as {@code 'issued'} so the FE only has to handle one
     * vocabulary.
     */
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

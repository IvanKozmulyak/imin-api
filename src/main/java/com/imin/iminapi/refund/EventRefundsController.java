package com.imin.iminapi.refund;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.refund.dto.EventRefundRowResponse;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Event-wide refund history for the EventDetailPage "Refund history" tab.
 * One round-trip returns every refund for an event with the buyer email and
 * short order code joined in — replaces the previous N-orders × per-order
 * /refunds fan-out the dashboard used to do.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/refunds")
public class EventRefundsController {

    private final EventRepository events;
    private final RefundRepository refunds;
    private final RefundTicketRepository refundTickets;

    public EventRefundsController(EventRepository events,
                                  RefundRepository refunds,
                                  RefundTicketRepository refundTickets) {
        this.events = events;
        this.refunds = refunds;
        this.refundTickets = refundTickets;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<EventRefundRowResponse> list(@PathVariable UUID eventId,
                                             @CurrentUser AuthPrincipal principal) {
        Event event = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!event.getOrgId().equals(principal.orgId())) throw ApiException.notFound("Event");

        List<Object[]> rows = refunds.findByEventIdWithOrder(eventId);
        if (rows.isEmpty()) return List.of();

        List<Refund> refundList = new ArrayList<>(rows.size());
        Map<UUID, String> emailByRefundId = new HashMap<>(rows.size());
        for (Object[] row : rows) {
            Refund r = (Refund) row[0];
            String email = (String) row[1];
            refundList.add(r);
            emailByRefundId.put(r.getId(), email);
        }

        Map<UUID, List<UUID>> ticketIdsByRefund = loadTicketIdsByRefund(
                refundList.stream().map(Refund::getId).toList());

        List<EventRefundRowResponse> out = new ArrayList<>(refundList.size());
        for (Refund r : refundList) {
            out.add(EventRefundRowResponse.from(
                r,
                emailByRefundId.get(r.getId()),
                ticketIdsByRefund.getOrDefault(r.getId(), List.of())));
        }
        return out;
    }

    private Map<UUID, List<UUID>> loadTicketIdsByRefund(List<UUID> refundIds) {
        if (refundIds.isEmpty()) return Map.of();
        Map<UUID, List<UUID>> byRefund = new HashMap<>(refundIds.size());
        for (Object[] pair : refundTickets.findRefundIdTicketIdPairs(refundIds)) {
            UUID refundId = (UUID) pair[0];
            UUID ticketId = (UUID) pair[1];
            byRefund.computeIfAbsent(refundId, k -> new ArrayList<>()).add(ticketId);
        }
        return byRefund;
    }
}

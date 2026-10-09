package com.imin.iminapi.refund;

import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.refund.event.RefundConfirmedEvent;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SUCCEEDED-path side effects of a refund: decrement {@code tier.sold}, flip the claimed tickets to
 * refunded, and publish the confirmation email. Joins the caller's transaction (the webhook's, or
 * {@link RefundAttemptStore#recordOutcome}); callers run it only after winning the status transition.
 */
@Component
public class RefundInventoryRelease {

    private static final Logger log = LoggerFactory.getLogger(RefundInventoryRelease.class);

    private final RefundTicketRepository refundTickets;
    private final TicketRepository tickets;
    private final TicketTierRepository tierRepository;
    private final ApplicationEventPublisher publisher;

    public RefundInventoryRelease(RefundTicketRepository refundTickets, TicketRepository tickets,
                                  TicketTierRepository tierRepository, ApplicationEventPublisher publisher) {
        this.refundTickets = refundTickets;
        this.tickets = tickets;
        this.tierRepository = tierRepository;
        this.publisher = publisher;
    }

    public void release(UUID refundId) {
        releaseInventoryAndMarkTickets(refundId);
        publisher.publishEvent(new RefundConfirmedEvent(refundId));
    }

    private void releaseInventoryAndMarkTickets(UUID refundId) {
        List<UUID> ticketIds = refundTickets.findTicketIdsByRefundId(refundId);
        List<Ticket> ticketsForRefund = tickets.findAllById(ticketIds);
        Map<UUID, Long> qtyByTier = ticketsForRefund.stream()
            .collect(Collectors.groupingBy(Ticket::getTierId, Collectors.counting()));

        // Lock tier rows in UUID.compareTo order so concurrent refunds cannot deadlock;
        // TicketTierService.lockForWrite locks in the same order.
        qtyByTier.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                TicketTier tier = tierRepository.findByIdForUpdate(entry.getKey())
                    .orElseThrow(() -> new IllegalStateException("Missing tier " + entry.getKey()));
                int decrement = entry.getValue().intValue();
                int currentSold = tier.getSold();
                if (currentSold < decrement) {
                    log.warn("[refund] tier {} sold={} < decrement={} — clamping (inventory drift)",
                        tier.getId(), currentSold, decrement);
                }
                tier.setSold(Math.max(0, currentSold - decrement));
                tierRepository.save(tier);
            });

        for (Ticket t : ticketsForRefund) t.setState(Ticket.STATE_REFUNDED);
        tickets.saveAll(ticketsForRefund);
        log.info("[refund] released {} ticket(s) across {} tier(s) for refund {}",
            ticketsForRefund.size(), qtyByTier.size(), refundId);
    }
}

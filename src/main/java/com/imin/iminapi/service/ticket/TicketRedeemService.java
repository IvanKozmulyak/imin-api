package com.imin.iminapi.service.ticket;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Atomic single-use redemption at the gate. The state transition runs as one
 * UPDATE with a {@code state in ('issued', 'pre')} predicate so two scanners
 * racing on the same QR can never both succeed.
 *
 * <p>Authorization is enforced HERE, not in the controller. The controller can
 * only check that the caller belongs to the org named in the path; it cannot
 * check that the event named in the path belongs to that org, and a gate
 * credential is org-scoped ({@code AuthPrincipal.forGate}) — it carries no
 * event scope at all. Without the ownership load below, org A's own token on
 * org A's path with org B's event id reached the atomic UPDATE and redeemed a
 * foreign ticket. Keeping the check in the service covers every future caller.
 *
 * <p>M1: publishes {@link TicketRedeemedEvent}(orderId, eventId) on successful
 * redemption. The audience projector listens AFTER_COMMIT + @Async. The
 * redeemer principal may be a gate device (userId null) — do not assume human actor.
 */
@Service
public class TicketRedeemService {

    public enum Outcome { REDEEMED, ALREADY_REDEEMED, WRONG_EVENT, REVOKED, REFUNDED, INVALID }

    public record Result(Outcome outcome, Ticket ticket) {}

    private final TicketRepository tickets;
    private final EventRepository events;
    private final QrPayloadSigner signer;
    private final ApplicationEventPublisher publisher;

    public TicketRedeemService(TicketRepository tickets, EventRepository events,
                               QrPayloadSigner signer,
                               ApplicationEventPublisher publisher) {
        this.tickets = tickets;
        this.events = events;
        this.signer = signer;
        this.publisher = publisher;
    }

    /**
     * @param callerOrgId the org the credential is scoped to. The event must belong
     *                    to it; a foreign or unknown event id answers
     *                    {@code 404 NOT_FOUND}, the same shape every other
     *                    org-scoped service uses for cross-org access, so the
     *                    response never confirms that the event exists.
     */
    @Transactional
    public Result redeem(UUID callerOrgId, UUID expectedEventId, String qrPayload, UUID userId) {
        Event event = events.findById(expectedEventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (callerOrgId == null || !callerOrgId.equals(event.getOrgId())) {
            throw ApiException.notFound("Event");
        }

        Optional<String> token = signer.verify(qrPayload);
        if (token.isEmpty()) return new Result(Outcome.INVALID, null);

        Optional<Ticket> opt = tickets.findByToken(token.get());
        if (opt.isEmpty()) return new Result(Outcome.INVALID, null);
        Ticket t = opt.get();

        if (!t.getEventId().equals(expectedEventId)) {
            // Don't leak the real event id — that would tell a curious scanner
            // which event this ticket belongs to.
            return new Result(Outcome.WRONG_EVENT, null);
        }
        if (Ticket.STATE_REFUNDED.equals(t.getState())) {
            return new Result(Outcome.REFUNDED, t);
        }
        if (Ticket.STATE_REVOKED.equals(t.getState())) {
            return new Result(Outcome.REVOKED, t);
        }

        int rows = tickets.redeemAtomic(t.getToken(), userId, Instant.now());
        Ticket fresh = tickets.findByToken(t.getToken()).orElse(t);
        if (rows == 1) {
            publisher.publishEvent(new TicketRedeemedEvent(fresh.getOrderId(), fresh.getEventId()));
            return new Result(Outcome.REDEEMED, fresh);
        }
        // 0 rows: refunded, revoked, or already redeemed between our SELECT and UPDATE.
        if (Ticket.STATE_REFUNDED.equals(fresh.getState())) {
            return new Result(Outcome.REFUNDED, fresh);
        }
        if (Ticket.STATE_REVOKED.equals(fresh.getState())) {
            return new Result(Outcome.REVOKED, fresh);
        }
        return new Result(Outcome.ALREADY_REDEEMED, fresh);
    }
}

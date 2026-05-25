package com.imin.iminapi.service.ticket;

import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.TicketRepository;
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
 * <p>Authorization (organizer is in the right org, event is theirs) is
 * enforced one level up in the controller.
 */
@Service
public class TicketRedeemService {

    public enum Outcome { REDEEMED, ALREADY_REDEEMED, WRONG_EVENT, REVOKED, REFUNDED, INVALID }

    public record Result(Outcome outcome, Ticket ticket) {}

    private final TicketRepository tickets;
    private final QrPayloadSigner signer;

    public TicketRedeemService(TicketRepository tickets, QrPayloadSigner signer) {
        this.tickets = tickets;
        this.signer = signer;
    }

    @Transactional
    public Result redeem(UUID expectedEventId, String qrPayload, UUID userId) {
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

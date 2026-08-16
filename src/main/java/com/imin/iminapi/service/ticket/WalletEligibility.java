package com.imin.iminapi.service.ticket;

import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Whether a ticket may be turned into a wallet pass. One rule, both wallets.
 *
 * <h2>Why this is the only revocation control we have</h2>
 *
 * <p>We do not run an Apple pass-update web service and we do not patch Google
 * objects (ADR-0004). A pass already sitting on a device therefore never
 * changes. The one thing entirely within our control is refusing to hand out a
 * <b>new</b> pass for a ticket that is already dead, and that is what this does.
 *
 * <p>It is not a security control. The door is
 * {@link TicketRedeemService#redeem}, which re-reads state inside a transaction
 * and returns {@code REFUNDED} / {@code REVOKED} regardless of what the buyer is
 * holding; the gate PWA paints both red and refuses admission. This exists so we
 * do not actively mint someone a fresh, official-looking artifact for a ticket we
 * already refunded — a buyer standing at a door with something that looks valid
 * on their phone is a support incident even when the scanner does the right
 * thing.
 *
 * <h2>The refusal set is exactly the door's "do not admit" set</h2>
 *
 * <p>{@code TicketRedeemService} treats two stored states as dead —
 * {@link Ticket#STATE_REFUNDED} and {@link Ticket#STATE_REVOKED} — and it checks
 * them twice, once before the atomic UPDATE and once after, to close the race.
 * Those two, and only those two, are refused here. The scanner's other red
 * outcomes ({@code WRONG_EVENT}, {@code INVALID}) are not ticket states at all:
 * they describe a scan, not a row, and cannot be evaluated at minting time.
 *
 * <p><b>Redeemed is deliberately allowed.</b> The door maps
 * {@code already_redeemed} to amber, not red, and re-adding a pass after entry is
 * harmless: a buyer whose phone died in the queue must not be locked out of their
 * own ticket record. {@code issued} — and the legacy {@code "pre"} synonym the
 * column may still carry on old rows — are live by construction.
 */
public final class WalletEligibility {

    private WalletEligibility() {}

    /**
     * Throws {@code 409} when the ticket is one the door would refuse.
     *
     * <p>Two distinct codes rather than one: a refund is a thing the buyer did
     * (or asked for) and {@code imin-public} already has copy for it, while a
     * revocation is something the organizer did to them. Collapsing both into
     * {@code INVALID_STATE} would leave the frontend unable to say which.
     */
    public static void assertLive(Ticket t) {
        if (t == null) {
            throw ApiException.notFound("Ticket");
        }
        if (Ticket.STATE_REFUNDED.equals(t.getState())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.TICKET_ALREADY_REFUNDED,
                    "This ticket was refunded and cannot be added to a wallet");
        }
        if (Ticket.STATE_REVOKED.equals(t.getState())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "This ticket was revoked and cannot be added to a wallet");
        }
    }

    /**
     * The same rule as a predicate, for the response contract that decides
     * whether to advertise a wallet CTA at all.
     */
    public static boolean isLive(Ticket t) {
        return t != null
                && !Ticket.STATE_REFUNDED.equals(t.getState())
                && !Ticket.STATE_REVOKED.equals(t.getState());
    }
}

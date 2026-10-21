package com.imin.iminapi.controller.gate;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.referral.ReferralPerkService;
import com.imin.iminapi.referral.dto.PerkGivenResponse;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import com.imin.iminapi.service.ticket.QrPayloadSigner;
import com.imin.iminapi.util.OrderNumber;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * POST /api/v1/gate/events/{eventId}/referral-perk/given {qrPayload}: door staff hand over a crew perk.
 *
 * <p>Gate-token only, scoped to the token's org (another org's event is 404). The scanned ticket must belong to the
 * event and be issued or redeemed; its order's unlock is marked given once, so two phones scanning the same buyer
 * give it once. Results: given | already_given | not_unlocked | invalid | wrong_event.
 */
@RestController
public class GateReferralPerkController {

    public record Req(String qrPayload) {}

    private final EventRepository events;
    private final TicketRepository tickets;
    private final QrPayloadSigner signer;
    private final ReferralPerkService perks;
    private final AuditLogger audit;

    public GateReferralPerkController(EventRepository events, TicketRepository tickets, QrPayloadSigner signer,
                                      ReferralPerkService perks, AuditLogger audit) {
        this.events = events;
        this.tickets = tickets;
        this.signer = signer;
        this.perks = perks;
        this.audit = audit;
    }

    @PostMapping("/api/v1/gate/events/{eventId}/referral-perk/given")
    public PerkGivenResponse given(@PathVariable UUID eventId, @RequestBody Req req, @CurrentUser AuthPrincipal me) {
        if (me == null || !me.isGate() || me.orgId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_MISSING, "Authentication required");
        }
        Event event = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!me.orgId().equals(event.getOrgId())) throw ApiException.notFound("Event");
        if (req == null || req.qrPayload() == null || req.qrPayload().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "qrPayload is required");
        }

        Optional<Ticket> ticket = signer.verify(req.qrPayload()).flatMap(tickets::findByToken);
        if (ticket.isEmpty()) return new PerkGivenResponse("invalid", null);
        Ticket t = ticket.get();
        // Same no-leak rule as redeem: another event's ticket says nothing about its perk.
        if (!t.getEventId().equals(eventId)) return new PerkGivenResponse("wrong_event", null);
        if (Ticket.STATE_REFUNDED.equals(t.getState()) || Ticket.STATE_REVOKED.equals(t.getState())) {
            return new PerkGivenResponse("invalid", null);
        }

        if (!perks.perksEnabled()) return new PerkGivenResponse("not_unlocked", null);
        // Heals an unlock the async listener missed; skipped once the row exists.
        if (perks.unlockOf(eventId, t.getOrderId()).isEmpty()) perks.evaluate(t.getOrderId());
        PerkGivenResponse r = perks.markGiven(eventId, t.getOrderId(), ReferralPerkService.VIA_GATE, me.actorLabel());
        if ("given".equals(r.result())) {
            // No buyer identity, as for gate admissions.
            audit.record(me, AuditActions.REFERRAL_PERK_GIVEN, "order", t.getOrderId(),
                    "Crew perk given at the gate — order " + OrderNumber.display(t.getOrderId())
                            + ", session " + me.sessionId() + ", actor " + me.actorLabel());
        }
        return r;
    }
}

package com.imin.iminapi.controller.event;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import com.imin.iminapi.service.ticket.TicketRedeemService;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * POST /api/v1/orgs/{orgId}/events/{eventId}/tickets/redeem
 *
 * <p>Authenticated organizer-side endpoint for the gate scanner. Body carries
 * the signed QR payload; the service performs HMAC verification + an atomic
 * UPDATE so double-scans cleanly return {@code already_redeemed}.
 */
@RestController
public class TicketRedeemController {

    public record Req(@NotBlank String qrPayload) {}

    private static final Logger log = LoggerFactory.getLogger(TicketRedeemController.class);

    private final TicketRedeemService service;
    private final AuditLogger audit;

    public TicketRedeemController(TicketRedeemService service, AuditLogger audit) {
        this.service = service;
        this.audit = audit;
    }

    @PostMapping("/api/v1/orgs/{orgId}/events/{eventId}/tickets/redeem")
    public ResponseEntity<Map<String, Object>> redeem(@PathVariable UUID orgId,
                                                       @PathVariable UUID eventId,
                                                       @RequestBody Req req,
                                                       @CurrentUser AuthPrincipal me) {
        if (me == null || me.orgId() == null || !me.orgId().equals(orgId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                    "Not a member of this organization");
        }
        if (req == null || req.qrPayload() == null || req.qrPayload().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "qrPayload is required");
        }

        // `userId` is null for gate-token requests, and tickets.redeemed_by_user_id
        // is therefore written null for every scan a door phone makes — which is
        // most of them. That column alone can identify nobody.
        //
        // This comment used to claim an audit/log trail keyed on me.actorLabel()
        // existed. It did not: there was no audit write anywhere on this path and
        // TicketRedeemService had no log statement at all, so which door admitted
        // whom was unreconstructable — a GDPR accountability gap, an
        // internal-fraud blind spot, and worse for having been documented as a
        // control that was there. The trail is written below, for real.
        TicketRedeemService.Result r = service.redeem(me.orgId(), eventId, req.qrPayload(), me.userId());
        if (r.outcome() == TicketRedeemService.Outcome.REDEEMED && r.ticket() != null) {
            recordAdmission(me, eventId, r);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", switch (r.outcome()) {
            case REDEEMED -> "redeemed";
            case ALREADY_REDEEMED -> "already_redeemed";
            case WRONG_EVENT -> "wrong_event";
            case REVOKED -> "revoked";
            case REFUNDED -> "refunded";
            case INVALID -> "invalid";
        });
        // wrong_event deliberately omits the ticket info so the response never
        // leaks which event the token actually belongs to.
        if (r.ticket() != null && r.outcome() != TicketRedeemService.Outcome.WRONG_EVENT) {
            Map<String, Object> tk = new LinkedHashMap<>();
            tk.put("token", r.ticket().getToken());
            tk.put("tierName", r.ticket().getTierName());
            if (r.ticket().getRedeemedAt() != null) {
                tk.put("redeemedAt", r.ticket().getRedeemedAt().toString());
            }
            body.put("ticket", tk);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * One audit row and one structured line per ticket actually admitted.
     *
     * <p>Written here rather than inside the service because the actor is an HTTP
     * concern: the service is handed a nullable user id and has no way to tell a
     * door phone from a human, which is exactly how the gap arose.
     *
     * <p>{@code audit_logs} rather than new {@code tickets} columns: it is already
     * org-scoped, already readable through {@code GET /orgs/{id}/audit}, and one
     * row per scan preserves a repeat attempt that a single "redeemed by" column
     * would overwrite. {@code AuditLogger} writes in its own transaction and
     * swallows failures, so a full disk cannot turn a valid ticket away at the
     * door.
     *
     * <p><b>No buyer identity in either.</b> The question this answers is which
     * door admitted a ticket, not who was holding it; putting the address here
     * would put attendee lists into the log pipeline (and into Sentry) on every
     * scan of the night.
     */
    private void recordAdmission(AuthPrincipal me, UUID eventId, TicketRedeemService.Result r) {
        String actor = me.actorLabel();
        String summary = "Ticket redeemed at the gate — event " + eventId
                + ", ticket " + r.ticket().getId()
                + ", session " + me.sessionId()
                + ", actor " + actor;
        log.info("[gate-redeem] event={} ticket={} session={} actor={}",
                eventId, r.ticket().getId(), me.sessionId(), actor);
        audit.record(me, AuditActions.TICKET_REDEEMED, "ticket", r.ticket().getId(), summary);
    }
}

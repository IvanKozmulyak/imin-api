package com.imin.iminapi.controller.event;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.ticket.TicketRedeemService;
import jakarta.validation.constraints.NotBlank;
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

    private final TicketRedeemService service;

    public TicketRedeemController(TicketRedeemService service) {
        this.service = service;
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

        // `userId` is null for gate-token requests — TicketRedeemService persists
        // the redeemed-by column as the user UUID (nullable), and the
        // audit/log trail uses me.actorLabel() ("gate:<orgId>" for gates) to
        // keep the actor identifiable without crashing on null.
        TicketRedeemService.Result r = service.redeem(eventId, req.qrPayload(), me.userId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", switch (r.outcome()) {
            case REDEEMED -> "redeemed";
            case ALREADY_REDEEMED -> "already_redeemed";
            case WRONG_EVENT -> "wrong_event";
            case REVOKED -> "revoked";
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
}

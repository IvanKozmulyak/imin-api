package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.service.ticket.OrderRecoveryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * POST /api/v1/public/orders/recover
 *
 * <p>Always responds 204. The body the buyer sees on the FE is the same
 * whether or not we found any orders for that email — keeps an attacker from
 * probing the database for which addresses purchased which events.
 */
@RestController
public class PublicRecoveryController {

    public record Req(String email, UUID eventId) {}

    private final OrderRecoveryService service;

    public PublicRecoveryController(OrderRecoveryService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/public/orders/recover")
    public ResponseEntity<Void> recover(@RequestBody Req req, HttpServletRequest http) {
        if (req != null) {
            service.requestRecovery(req.email(), req.eventId(), http.getRemoteAddr());
        }
        return ResponseEntity.noContent().build();
    }
}

package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.service.ticket.CheckoutStatusService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * GET /api/v1/public/checkout/{sessionId}
 *
 * <p>Used by the imin-public success page to learn whether the issuance
 * webhook has finished for a buyer just redirected from Stripe. Returns
 * {@code {status: "ready", orderToken}} once the Order exists, otherwise
 * {@code {status: "pending"}}. The Stripe session id in the URL is the
 * authorization — only the buyer's browser receives it from Stripe's
 * redirect, and {@code Cache-Control: private, no-store} keeps it out of
 * shared caches.
 */
@RestController
public class PublicCheckoutController {

    private final CheckoutStatusService statusService;

    public PublicCheckoutController(CheckoutStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/api/v1/public/checkout/{sessionId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String sessionId) {
        CheckoutStatusService.Result r = statusService.statusFor(sessionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", r.status().name().toLowerCase(Locale.ROOT));
        if (r.orderToken() != null) {
            body.put("orderToken", r.orderToken());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }
}

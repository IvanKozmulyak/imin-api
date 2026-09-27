package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audienceplan.dto.DoorOptInPageResponse;
import com.imin.iminapi.audienceplan.dto.DoorOptInRequest;
import com.imin.iminapi.audienceplan.dto.DoorOptInResponse;
import com.imin.iminapi.audienceplan.service.DoorOptInService;
import com.imin.iminapi.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Unauthenticated door QR page; the per-event token from the QR is the only credential. */
@RestController
@RequestMapping("/api/v1/public/events/{eventId}/door-optin")
public class PublicDoorOptInController {

    private final DoorOptInService service;
    private final RateLimiter rateLimiter;

    public PublicDoorOptInController(DoorOptInService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public ResponseEntity<DoorOptInPageResponse> page(@PathVariable UUID eventId,
                                                      @RequestParam(name = "t", required = false) String token) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.page(eventId, token));
    }

    /** Charged before any lookup or validation, per client IP (getRemoteAddr, resolved from the proxy). */
    @PostMapping
    public DoorOptInResponse optIn(@PathVariable UUID eventId,
                                   @RequestBody(required = false) DoorOptInRequest body,
                                   HttpServletRequest http) {
        rateLimiter.consume("door-optin", "ip:" + http.getRemoteAddr());
        return service.optIn(eventId, body);
    }
}

package com.imin.iminapi.audience.controller;

import com.imin.iminapi.audience.dto.ConsentConfirmationResponse;
import com.imin.iminapi.audience.service.ConsentConfirmationService;
import com.imin.iminapi.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The link in a door QR / survey confirmation email. GET is read-only and unmetered (the buyer site calls it from
 * one server IP); POST confirms and is charged per IP. Every failure is the same 200 {@code invalid}.
 */
@RestController
@RequestMapping("/api/v1/public/consent/confirm")
public class PublicConsentConfirmController {

    static final String BUCKET = "consent-confirm";

    private final ConsentConfirmationService service;
    private final RateLimiter rateLimiter;

    public PublicConsentConfirmController(ConsentConfirmationService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public ResponseEntity<ConsentConfirmationResponse> preview(@RequestParam(name = "t", required = false) String token) {
        return noStore(service.preview(token));
    }

    @PostMapping
    public ResponseEntity<ConsentConfirmationResponse> confirm(@RequestParam(name = "t", required = false) String token,
                                                               HttpServletRequest http) {
        rateLimiter.consume(BUCKET, "ip:" + http.getRemoteAddr());
        return noStore(service.confirm(token));
    }

    private static ResponseEntity<ConsentConfirmationResponse> noStore(ConsentConfirmationResponse body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}

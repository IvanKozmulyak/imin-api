package com.imin.iminapi.checkoutreminder.controller;

import com.imin.iminapi.checkoutreminder.dto.CheckoutReminderPreviewDto;
import com.imin.iminapi.checkoutreminder.dto.CheckoutReminderSummaryDto;
import com.imin.iminapi.checkoutreminder.dto.CheckoutReminderToggleRequest;
import com.imin.iminapi.checkoutreminder.service.CheckoutReminderService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.RateLimiter;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The unfinished-checkout reminder for the caller's org (org from the principal, no id in the path).
 * GET, preview and test: any member; preview and test answer 429 RATE_LIMITED past their per-user
 * buckets. PUT: owner or admin (403 otherwise); turning on answers 409 INVALID_STATE while the
 * platform switch is off and 400 FIELD_INVALID {legalName, legalContact} without the legal identity
 * the footer needs.
 */
@RestController
@RequestMapping("/api/v1/marketing/checkout-reminders")
public class CheckoutReminderController {

    private final CheckoutReminderService service;
    private final RateLimiter rateLimiter;

    public CheckoutReminderController(CheckoutReminderService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public CheckoutReminderSummaryDto summary(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.summary(principal);
    }

    @PutMapping
    public CheckoutReminderSummaryDto toggle(@AuthenticationPrincipal AuthPrincipal principal,
                                             @Valid @RequestBody CheckoutReminderToggleRequest body) {
        return service.toggle(principal, body.enabled());
    }

    @GetMapping("/preview")
    public CheckoutReminderPreviewDto preview(@AuthenticationPrincipal AuthPrincipal principal,
                                              @RequestParam(required = false) String locale,
                                              @RequestParam(required = false) Integer qty) {
        rateLimiter.consume("checkout-reminder-preview", principal.userId().toString());
        return service.preview(principal, locale, qty);
    }

    /** Sends one "[TEST]" copy to the caller's own address; 409 when no night is on sale. */
    @PostMapping("/test")
    public ResponseEntity<Void> test(@AuthenticationPrincipal AuthPrincipal principal,
                                     @RequestParam(required = false) String locale) {
        rateLimiter.consume("checkout-reminder-test", principal.userId().toString());
        service.testSend(principal, locale);
        return ResponseEntity.noContent().build();
    }
}

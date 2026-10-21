package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.referral.ReferralService;
import com.imin.iminapi.referral.dto.PublicReferralLookupResponse;
import com.imin.iminapi.security.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * GET /api/v1/public/events/{eventId}/referrals/{code}: does this crew link count, and for which perk? Unknown
 * code, another event's code or an ineligible event all answer the same leak-safe 404 the event page uses.
 */
@RestController
public class PublicReferralController {

    private final ReferralService referrals;

    public PublicReferralController(ReferralService referrals) {
        this.referrals = referrals;
    }

    @GetMapping("/api/v1/public/events/{eventId}/referrals/{code}")
    public ResponseEntity<PublicReferralLookupResponse> lookup(@PathVariable UUID eventId, @PathVariable String code) {
        PublicReferralLookupResponse body = referrals.lookup(eventId, code)
                .orElseThrow(() -> ApiException.notFound("Event"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=60")
                .body(body);
    }
}

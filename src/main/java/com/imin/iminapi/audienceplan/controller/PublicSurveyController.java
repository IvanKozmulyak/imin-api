package com.imin.iminapi.audienceplan.controller;

import com.imin.iminapi.audienceplan.dto.SurveyPageResponse;
import com.imin.iminapi.audienceplan.dto.SurveyResponseRequest;
import com.imin.iminapi.audienceplan.dto.SurveyResponseResult;
import com.imin.iminapi.audienceplan.service.SurveyService;
import com.imin.iminapi.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated post-event survey; the per-event token in the path is the only credential. */
@RestController
@RequestMapping("/api/v1/public/surveys/{token}")
public class PublicSurveyController {

    private final SurveyService service;
    private final RateLimiter rateLimiter;

    public PublicSurveyController(SurveyService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public ResponseEntity<SurveyPageResponse> page(@PathVariable String token) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.page(token));
    }

    /** Charged before any lookup or validation, per client IP (getRemoteAddr, resolved from the proxy). */
    @PostMapping
    public SurveyResponseResult submit(@PathVariable String token,
                                       @RequestBody(required = false) SurveyResponseRequest body,
                                       HttpServletRequest http) {
        rateLimiter.consume("survey", "ip:" + http.getRemoteAddr());
        return service.submit(token, body);
    }
}

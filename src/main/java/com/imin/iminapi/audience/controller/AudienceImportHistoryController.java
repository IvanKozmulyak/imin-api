package com.imin.iminapi.audience.controller;

import com.imin.iminapi.audience.dto.AudienceImportSummary;
import com.imin.iminapi.audience.service.AudienceImportHistoryService;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Import history for the Audience "Imports" tab; org comes from the auth context only. */
@RestController
@RequestMapping("/api/v1/audience/imports")
public class AudienceImportHistoryController {

    private final AudienceImportHistoryService historyService;

    public AudienceImportHistoryController(AudienceImportHistoryService historyService) {
        this.historyService = historyService;
    }

    /** Newest first, OWNER/ADMIN only (403 otherwise); {@code limit} defaults to 50, max 200. */
    @GetMapping
    public List<AudienceImportSummary> list(@AuthenticationPrincipal AuthPrincipal principal,
                                            @RequestParam(value = "limit", required = false) Integer limit) {
        return historyService.list(principal, limit);
    }
}

package com.imin.iminapi.controller.dashboard;

import com.imin.iminapi.dto.dashboard.DashboardPulseResponse;
import com.imin.iminapi.dto.dashboard.DashboardResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.dashboard.DashboardPeriod;
import com.imin.iminapi.service.dashboard.DashboardPulseService;
import com.imin.iminapi.service.dashboard.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService service;
    private final DashboardPulseService pulseService;

    public DashboardController(DashboardService service, DashboardPulseService pulseService) {
        this.service = service;
        this.pulseService = pulseService;
    }

    @GetMapping
    public DashboardResponse get(
            @CurrentUser AuthPrincipal p,
            @RequestParam(name = "cyclePeriod", required = false, defaultValue = "30d") String cyclePeriod,
            @RequestParam(name = "businessPeriod", required = false, defaultValue = "90d") String businessPeriod) {
        return service.build(p, DashboardPeriod.parse(cyclePeriod), DashboardPeriod.parse(businessPeriod));
    }

    /** On-sale state and the newest sale, org-wide or for one event of the caller's org (404 otherwise). */
    @GetMapping("/pulse")
    public DashboardPulseResponse pulse(
            @CurrentUser AuthPrincipal p,
            @RequestParam(name = "eventId", required = false) UUID eventId) {
        return pulseService.pulse(p, eventId);
    }
}

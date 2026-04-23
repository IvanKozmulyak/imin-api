package com.imin.iminapi.controller.dashboard;

import com.imin.iminapi.dto.dashboard.DashboardResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.dashboard.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) { this.service = service; }

    @GetMapping
    public DashboardResponse get(@CurrentUser AuthPrincipal p) {
        return service.build(p);
    }
}

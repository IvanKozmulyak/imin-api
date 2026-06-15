package com.imin.iminapi.controller.event;

import com.imin.iminapi.dto.event.SalesDashboardResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.event.SalesDashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
public class SalesDashboardController {

    private final SalesDashboardService dashboard;

    public SalesDashboardController(SalesDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/{id}/sales/live")
    public SalesDashboardResponse salesLive(@CurrentUser AuthPrincipal p, @PathVariable UUID id) {
        return dashboard.dashboard(p, id);
    }
}

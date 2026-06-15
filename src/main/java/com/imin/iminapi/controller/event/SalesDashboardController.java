package com.imin.iminapi.controller.event;

import com.imin.iminapi.dto.event.SalesDashboardResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.event.AttendeeExportService;
import com.imin.iminapi.service.event.SalesDashboardService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
public class SalesDashboardController {

    private final SalesDashboardService dashboard;
    private final AttendeeExportService attendeeExport;

    public SalesDashboardController(SalesDashboardService dashboard,
                                    AttendeeExportService attendeeExport) {
        this.dashboard = dashboard;
        this.attendeeExport = attendeeExport;
    }

    @GetMapping("/{id}/sales/live")
    public SalesDashboardResponse salesLive(@CurrentUser AuthPrincipal p, @PathVariable UUID id) {
        return dashboard.dashboard(p, id);
    }

    @GetMapping(value = "/{id}/attendees/export", produces = "text/csv")
    public ResponseEntity<String> exportAttendees(@CurrentUser AuthPrincipal p, @PathVariable UUID id) {
        String csv = attendeeExport.toCsv(p, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"attendees-" + id + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(csv);
    }
}

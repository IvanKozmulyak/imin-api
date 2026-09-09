package com.imin.iminapi.controller.event;

import com.imin.iminapi.dto.event.SalesDashboardResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
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
    private final AuditLogger audit;

    public SalesDashboardController(SalesDashboardService dashboard,
                                    AttendeeExportService attendeeExport,
                                    AuditLogger audit) {
        this.dashboard = dashboard;
        this.attendeeExport = attendeeExport;
        this.audit = audit;
    }

    @GetMapping("/{id}/sales/live")
    public SalesDashboardResponse salesLive(@CurrentUser AuthPrincipal p, @PathVariable UUID id) {
        return dashboard.dashboard(p, id);
    }

    /**
     * Hands a team member a file of every attendee's name and email address —
     * the largest single disclosure of personal data this API performs, and the
     * one that leaves the platform entirely. It wrote no record of having
     * happened, so an org could not answer "who took a copy of our list, and
     * when" — which is the question both an Art. 30 record and a leak
     * investigation start from. The row is written after the CSV is built, so a
     * request that 403s or 404s does not read as an export.
     */
    @GetMapping(value = "/{id}/attendees/export", produces = "text/csv")
    public ResponseEntity<String> exportAttendees(@CurrentUser AuthPrincipal p, @PathVariable UUID id) {
        // OWNER/ADMIN only. Given what the javadoc above says this endpoint hands
        // over, "any authenticated team member" was the wrong audience for it.
        com.imin.iminapi.security.RoleGuard.requireAtLeast(
                p, com.imin.iminapi.model.UserRole.ADMIN, "export the attendee list");
        String csv = attendeeExport.toCsv(p, id);
        audit.record(p, AuditActions.ATTENDEES_EXPORTED, "event", id,
                "Attendee CSV exported (" + csvRowCount(csv) + " row(s))");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"attendees-" + id + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(csv);
    }

    /** Data rows, i.e. excluding the header. Never the addresses themselves. */
    private static int csvRowCount(String csv) {
        if (csv == null || csv.isBlank()) return 0;
        int lines = (int) csv.lines().filter(l -> !l.isBlank()).count();
        return Math.max(0, lines - 1);
    }
}

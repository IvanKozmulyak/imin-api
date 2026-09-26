package com.imin.iminapi.audience.controller;

import com.imin.iminapi.audience.service.ConsentExportService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/** Per-organizer export of the consent trail; org comes from the auth context only. */
@RestController
@RequestMapping("/api/v1/audience/consent")
public class ConsentExportController {

    private final ConsentExportService exportService;
    private final AuditLogger audit;

    public ConsentExportController(ConsentExportService exportService, AuditLogger audit) {
        this.exportService = exportService;
        this.audit = audit;
    }

    /**
     * Every consent record of the caller's org as CSV, OWNER/ADMIN only. The role is checked
     * before streaming starts, so a refused request writes no audit row.
     */
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> export(@AuthenticationPrincipal AuthPrincipal principal) {
        exportService.requirePrivileged(principal);
        StreamingResponseBody body = out -> stream(principal, out);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"consent-records.csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    void stream(AuthPrincipal principal, OutputStream out) throws IOException {
        AtomicLong rows = new AtomicLong();
        try {
            Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            exportService.write(principal.orgId(), w, rows);
            w.flush();
        } catch (RuntimeException | IOException e) {
            // Rows already sent did leave the platform; AuditLogger never throws, so e survives.
            audit.record(principal, AuditActions.CONSENT_EXPORTED, "organization", principal.orgId(),
                    "Consent export interrupted after " + rows.get() + " row(s)");
            throw e;
        }
        audit.record(principal, AuditActions.CONSENT_EXPORTED, "organization", principal.orgId(),
                "Consent records exported (" + rows.get() + " row(s))");
    }
}

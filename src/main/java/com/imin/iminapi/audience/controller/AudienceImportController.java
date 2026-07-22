package com.imin.iminapi.audience.controller;

import com.imin.iminapi.audience.dto.ImportResultResponse;
import com.imin.iminapi.audience.service.AudienceImportService;
import com.imin.iminapi.audience.service.CsvContactParser;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Audience contact CSV import.
 * Base path: {@code /api/v1/audience/import}. orgId comes ONLY from the auth context.
 *
 * <p>Compliance posture: auto-subscribing on an organizer's assertion is a GDPR risk. The
 * required {@code attestation=true} flag, the {@code source='organizer_import'} audit trail,
 * and the absolute suppression-respect (see {@link AudienceImportService}) are what make it
 * defensible. This controller is the enforcement point for the attestation + size/row caps.
 */
@RestController
@RequestMapping("/api/v1/audience/import")
public class AudienceImportController {

    /** ~5MB file cap. */
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    /** ~10k data-row cap. */
    static final int MAX_ROWS = 10_000;

    private final AudienceImportService importService;
    private final RateLimiter rateLimiter;

    public AudienceImportController(AudienceImportService importService, RateLimiter rateLimiter) {
        this.importService = importService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ImportResultResponse importCsv(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "attestation", required = false) String attestation,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun) {

        // Attestation is the load-bearing consent gate — reject before any parsing or writes.
        if (!"true".equals(attestation)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.IMPORT_ATTESTATION_REQUIRED,
                    "You must attest that you have consent to contact these people (attestation=true)");
        }

        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.IMPORT_FILE_REQUIRED,
                    "A non-empty CSV file is required");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.IMPORT_FILE_TOO_LARGE,
                    "CSV file exceeds the 5MB maximum");
        }

        // Rate-limit per org — imports are heavy + consent-sensitive.
        rateLimiter.consume("audience-import", principal.orgId().toString());

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException io) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.IMPORT_INVALID_CSV,
                    "Could not read the uploaded file");
        }

        List<CsvContactParser.RawContact> rows = CsvContactParser.parse(bytes, MAX_ROWS);
        return importService.importContacts(rows, dryRun, principal);
    }
}

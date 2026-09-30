package com.imin.iminapi.audience.controller;

import com.imin.iminapi.audience.dto.ImportResultResponse;
import com.imin.iminapi.audience.service.AudienceImportService;
import com.imin.iminapi.audience.service.CsvContactParser;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.security.RoleGuard;
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
 * <p>OWNER/ADMIN only (403 otherwise), checked before the attestation, parsing and rate limit.
 *
 * <p>Compliance posture: only a row carrying its own proof of explicit consent is subscribed
 * (see {@link AudienceImportService}); the required {@code attestation=true} flag records that the
 * organizer vouches for that per-row evidence, and never subscribes anyone by itself. This
 * controller is the enforcement point for the attestation + size/row caps, and records the
 * file's SHA-256 and the optional import-level {@code proofRef}.
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
            // Which revision of the attestation statement the dashboard displayed.
            // Optional so an older dashboard keeps working; absent is recorded as
            // "unversioned" rather than guessed at.
            @RequestParam(value = "attestationVersion", required = false) String attestationVersion,
            // Import-level evidence (e.g. a link to the platform's consent export); lifts the
            // per-import cap on subscribed rows.
            @RequestParam(value = "proofRef", required = false) String proofRef,
            // SHA-256 of the organizer's original file, computed in the browser before the
            // dashboard rewrites it; stored only when it is 64 hex characters.
            @RequestParam(value = "originalFileSha256", required = false) String originalFileSha256,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun) {

        // Signing the attestation binds the org, so only an owner/admin may import (dry runs too).
        RoleGuard.requireAtLeast(principal, UserRole.ADMIN, "import contacts");

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
        return importService.importContacts(rows, dryRun, principal,
                new AudienceImportService.ImportOptions(attestationVersion, sha256Hex(bytes),
                        sha256OrNull(originalFileSha256), proofRef));
    }

    static String sha256OrNull(String supplied) {
        if (supplied == null) return null;
        String s = supplied.trim().toLowerCase(java.util.Locale.ROOT);
        return s.matches("[0-9a-f]{64}") ? s : null;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

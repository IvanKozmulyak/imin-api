package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.service.CsvContactParser.RawContact;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Decides, per imported row, what consent basis the row's own provenance supports.
 *
 * <p>Default is no basis. Only {@code marketing_status=opted_in} with a proof reference,
 * a source platform and a valid export date yields explicit consent; a whole-file
 * attestation never does. Pure: no Spring, no I/O.
 */
public final class ImportValidator {

    /** Subscribed rows accepted per import before import-level evidence is required. */
    public static final int SUBSCRIBED_CAP = 2_000;

    public static final String STATUS_OPTED_IN = "opted_in";
    public static final String STATUS_UNSUBSCRIBED = "unsubscribed";
    public static final String STATUS_NONE = "none";

    public static final String REASON_NOT_OPTED_IN = "not_opted_in";
    public static final String REASON_MISSING_PROOF = "missing_proof";
    public static final String REASON_INVALID_EXPORT_DATE = "invalid_export_date";
    public static final String REASON_SUBSCRIBED_CAP = "subscribed_cap";
    public static final String REASON_UNSUBSCRIBED = "unsubscribed";

    static final int MAX_SOURCE_PLATFORM = 64;
    static final int MAX_PROOF_REF = 500;
    static final int MAX_EVENTS = 2_000;

    public enum Outcome { EXPLICIT, NO_BASIS, UNSUBSCRIBE }

    /** The row's normalised provenance and what it supports; {@code reason} is null for EXPLICIT. */
    public record Decision(Outcome outcome, String reason, String marketingStatus,
                           String sourcePlatform, LocalDate exportDate, LocalDate lastPurchaseDate,
                           String events, String proofRef) {}

    private final boolean importLevelProof;
    private final LocalDate today;
    private int subscribed;

    private ImportValidator(boolean importLevelProof, LocalDate today) {
        this.importLevelProof = importLevelProof;
        this.today = today;
    }

    /** One validator per import: the subscribed cap counts across its rows. */
    public static ImportValidator forImport(boolean importLevelProof, LocalDate today) {
        return new ImportValidator(importLevelProof, today);
    }

    /** Decide a row that will land as a member; an explicit decision counts towards the cap. */
    public Decision decide(RawContact row) {
        Decision d = inspect(row);
        if (d.outcome() != Outcome.EXPLICIT) return d;
        if (!importLevelProof && subscribed >= SUBSCRIBED_CAP) {
            return withOutcome(d, Outcome.NO_BASIS, REASON_SUBSCRIBED_CAP);
        }
        subscribed++;
        return d;
    }

    /** Decide a row without touching the cap (rows that will not be subscribed anyway). */
    public Decision inspect(RawContact row) {
        String status = normaliseStatus(row.marketingStatus());
        String platform = clip(row.sourcePlatform(), MAX_SOURCE_PLATFORM);
        String proof = clip(row.proofRef(), MAX_PROOF_REF);
        String events = clip(row.events(), MAX_EVENTS);
        LocalDate exportDate = parseDate(row.exportDate());
        LocalDate lastPurchase = parseDate(row.lastPurchaseDate());
        boolean exportDateValid = exportDate != null && !exportDate.isAfter(today);

        Outcome outcome;
        String reason;
        if (STATUS_UNSUBSCRIBED.equals(status)) {
            outcome = Outcome.UNSUBSCRIBE;
            reason = REASON_UNSUBSCRIBED;
        } else if (!STATUS_OPTED_IN.equals(status)) {
            outcome = Outcome.NO_BASIS;
            reason = REASON_NOT_OPTED_IN;
        } else if (proof == null || platform == null || blank(row.exportDate())) {
            outcome = Outcome.NO_BASIS;
            reason = REASON_MISSING_PROOF;
        } else if (!exportDateValid) {
            outcome = Outcome.NO_BASIS;
            reason = REASON_INVALID_EXPORT_DATE;
        } else {
            outcome = Outcome.EXPLICIT;
            reason = null;
        }
        return new Decision(outcome, reason, status, platform,
                exportDateValid ? exportDate : null, lastPurchase, events, proof);
    }

    private static Decision withOutcome(Decision d, Outcome outcome, String reason) {
        return new Decision(outcome, reason, d.marketingStatus(), d.sourcePlatform(), d.exportDate(),
                d.lastPurchaseDate(), d.events(), d.proofRef());
    }

    static String normaliseStatus(String raw) {
        if (raw == null) return STATUS_NONE;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (STATUS_OPTED_IN.equals(s)) return STATUS_OPTED_IN;
        if (STATUS_UNSUBSCRIBED.equals(s)) return STATUS_UNSUBSCRIBED;
        return STATUS_NONE;
    }

    /** ISO {@code yyyy-MM-dd} only; anything else is treated as absent. */
    static LocalDate parseDate(String raw) {
        if (blank(raw)) return null;
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String clip(String raw, int max) {
        if (blank(raw)) return null;
        String t = raw.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

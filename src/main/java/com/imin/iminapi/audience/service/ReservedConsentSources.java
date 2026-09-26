package com.imin.iminapi.audience.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code consent_records.source} values only system paths write; an organizer-typed capture
 * must not claim one, since ConsentGate trusts some of them as evidence.
 */
public final class ReservedConsentSources {

    static final Set<String> EXACT = Set.of(
            "checkout",               // AudienceOrderProjector
            "organizer_import",       // AudienceImportService (bulk attestation)
            "organizer_import_row",   // CSV import with a provenance row
            "door_qr",                // door QR opt-in
            "survey",                 // post-event survey opt-in
            "preference_centre_row",  // BuyerPreferencesService
            "order_confirmation",     // SmsConsentService
            "one_click",              // PublicUnsubscribeController (RFC 8058)
            "footer_link",            // marketing_optouts vocabulary (V85)
            "buyer_account_deletion", // BuyerAccountDeletionService
            "sms_stop"                // SmsStopService
    );

    static final List<String> PREFIXES = List.of(
            "dsar_",       // DsarService: dsar_object, dsar_erase
            "retention_",  // retention job
            "sms_stop_"    // SmsStopService: sms_stop_reply
    );

    private static final Pattern FORMAT_CHARS = Pattern.compile("\\p{Cf}");
    private static final Pattern TRAILING_PUNCT = Pattern.compile("\\p{Punct}+$");

    private ReservedConsentSources() {}

    /** True when {@code source}, after {@link #normalise}, is a system-only value. */
    public static boolean isReserved(String source) {
        if (source == null) return false;
        String s = normalise(source);
        if (EXACT.contains(s)) return true;
        for (String prefix : PREFIXES) {
            if (s.startsWith(prefix)) return true;
        }
        return false;
    }

    // ponytail: cross-script lookalikes (Cyrillic "с") pass; readers match exact strings, so no gate bypass.
    static String normalise(String source) {
        String s = Normalizer.normalize(source, Normalizer.Form.NFKC);
        s = FORMAT_CHARS.matcher(s).replaceAll("").strip();
        s = TRAILING_PUNCT.matcher(s).replaceAll("").strip();
        return s.toLowerCase(Locale.ROOT);
    }
}

package com.imin.iminapi.util;

import java.util.Map;
import java.util.Optional;

/**
 * Static ISO 3166-1 alpha-2 country code &rarr; primary IANA time-zone id mapping, used to derive
 * an event's timezone from its venue country when the organizer didn't pick one explicitly
 * (bug 86ca74h6c: events were stored with the {@code 'UTC'} default, so public pages rendered a
 * 20:00 Paris show as 18:00).
 *
 * <p><b>Coverage.</b> imin is an EEA-based, FR-primary platform, so the map covers Europe broadly
 * (all EU-27 + EEA + micro-states + the Western Balkans) plus the handful of non-European
 * countries that Stripe/our onboarding actually admits and that show up in real venue data
 * (GB, CH, US, CA). Countries outside this set have no derivable zone here and fall back to the
 * caller's default (UTC) — an explicit timezone on the request is always honoured over this map.
 *
 * <p><b>Ceiling — multi-zone countries.</b> A single country can span several IANA zones. We map
 * each to the zone of its <i>capital / mainland</i> and knowingly ignore outlying territories:
 * <ul>
 *   <li>FR &rarr; Europe/Paris (ignores the overseas départements/territories)</li>
 *   <li>ES &rarr; Europe/Madrid (ignores the Canary Islands, Atlantic/Canary)</li>
 *   <li>PT &rarr; Europe/Lisbon (ignores the Azores &amp; Madeira)</li>
 *   <li>US &rarr; America/New_York (capital DC; ignores the 5+ other continental/island zones)</li>
 *   <li>CA &rarr; America/Toronto (capital Ottawa's zone; ignores the 5 other zones)</li>
 * </ul>
 * This is deliberately a country-level heuristic; when it's wrong the organizer edits the event's
 * timezone explicitly. Keep in sync with the {@code V75__event_timezone_backfill.sql} CASE map.
 */
public final class CountryTimeZones {

    /** ISO 3166-1 alpha-2 (uppercase) &rarr; primary IANA zone id. */
    private static final Map<String, String> ZONES = Map.ofEntries(
            // EU-27
            Map.entry("AT", "Europe/Vienna"),
            Map.entry("BE", "Europe/Brussels"),
            Map.entry("BG", "Europe/Sofia"),
            Map.entry("HR", "Europe/Zagreb"),
            Map.entry("CY", "Asia/Nicosia"),
            Map.entry("CZ", "Europe/Prague"),
            Map.entry("DK", "Europe/Copenhagen"),
            Map.entry("EE", "Europe/Tallinn"),
            Map.entry("FI", "Europe/Helsinki"),
            Map.entry("FR", "Europe/Paris"),
            Map.entry("DE", "Europe/Berlin"),
            Map.entry("GR", "Europe/Athens"),
            Map.entry("HU", "Europe/Budapest"),
            Map.entry("IE", "Europe/Dublin"),
            Map.entry("IT", "Europe/Rome"),
            Map.entry("LV", "Europe/Riga"),
            Map.entry("LT", "Europe/Vilnius"),
            Map.entry("LU", "Europe/Luxembourg"),
            Map.entry("MT", "Europe/Malta"),
            Map.entry("NL", "Europe/Amsterdam"),
            Map.entry("PL", "Europe/Warsaw"),
            Map.entry("PT", "Europe/Lisbon"),
            Map.entry("RO", "Europe/Bucharest"),
            Map.entry("SK", "Europe/Bratislava"),
            Map.entry("SI", "Europe/Ljubljana"),
            Map.entry("ES", "Europe/Madrid"),
            Map.entry("SE", "Europe/Stockholm"),
            // EEA (non-EU) + European micro-states / neighbours
            Map.entry("NO", "Europe/Oslo"),
            Map.entry("IS", "Atlantic/Reykjavik"),
            Map.entry("LI", "Europe/Vaduz"),
            Map.entry("CH", "Europe/Zurich"),
            Map.entry("GB", "Europe/London"),
            Map.entry("GI", "Europe/Gibraltar"),
            Map.entry("MC", "Europe/Monaco"),
            Map.entry("AD", "Europe/Andorra"),
            Map.entry("SM", "Europe/San_Marino"),
            Map.entry("VA", "Europe/Vatican"),
            Map.entry("UA", "Europe/Kyiv"),
            Map.entry("MD", "Europe/Chisinau"),
            Map.entry("RS", "Europe/Belgrade"),
            Map.entry("ME", "Europe/Podgorica"),
            Map.entry("BA", "Europe/Sarajevo"),
            Map.entry("MK", "Europe/Skopje"),
            Map.entry("AL", "Europe/Tirane"),
            Map.entry("XK", "Europe/Belgrade"), // Kosovo shares the Belgrade zone
            // Non-European countries that appear in real venue data / are onboarding-eligible.
            // Multi-zone: mapped to the capital's zone (see class ceiling note).
            Map.entry("US", "America/New_York"),
            Map.entry("CA", "America/Toronto")
    );

    private CountryTimeZones() {}

    /**
     * The primary IANA zone id for a country, or empty when the code is null/blank/unmapped.
     * Input is case-insensitive; a surrounding trim is applied.
     */
    public static Optional<String> zoneFor(String isoAlpha2) {
        if (isoAlpha2 == null) return Optional.empty();
        String key = isoAlpha2.trim().toUpperCase(java.util.Locale.ROOT);
        if (key.isEmpty()) return Optional.empty();
        return Optional.ofNullable(ZONES.get(key));
    }
}

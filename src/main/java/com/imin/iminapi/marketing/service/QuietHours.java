package com.imin.iminapi.marketing.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Server-enforced quiet hours (spec §7). Email is blocked 22:00–09:00 in the
 * org's local timezone (organizations.timezone; UTC fallback). Half-open
 * window [22:00, 09:00): 09:00 sharp is allowed, 22:00 sharp is quiet. No
 * override — the dispatcher hard-blocks.
 */
@Component
public class QuietHours {

    private static final LocalTime EMAIL_QUIET_START = LocalTime.of(22, 0); // inclusive
    private static final LocalTime EMAIL_QUIET_END = LocalTime.of(9, 0);    // exclusive

    public boolean isEmailQuiet(String timezone, Instant now) {
        ZoneId zone = resolveZone(timezone);
        LocalTime local = now.atZone(zone).toLocalTime();
        // Window wraps midnight: quiet if >= 22:00 OR < 09:00.
        return !local.isBefore(EMAIL_QUIET_START) || local.isBefore(EMAIL_QUIET_END);
    }

    private ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) return ZoneId.of("UTC");
        try { return ZoneId.of(timezone); }
        catch (Exception e) { return ZoneId.of("UTC"); }
    }
}

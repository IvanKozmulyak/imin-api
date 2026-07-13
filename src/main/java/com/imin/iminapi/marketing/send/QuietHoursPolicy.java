package com.imin.iminapi.marketing.send;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Send-window seam (spec §2.5 step 4 / §7). Phase 2 ships PERMISSIVE by default
 * (imin.marketing.quiet-hours-enabled=false) so email send-now is unblocked; Phase 4
 * flips it on and adds the org-local 22:00–09:00 block enforced in the dispatcher.
 * Placing the seam here now means the dispatcher's call site never changes.
 */
@Component
public class QuietHoursPolicy {

    private final boolean enabled;

    public QuietHoursPolicy(
            @Value("${imin.marketing.quiet-hours-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    /** True when email may be sent right now for an org in the given timezone. */
    public boolean canSendNow(String orgTimezone) {
        if (!enabled) return true;
        ZoneId zone;
        try {
            zone = ZoneId.of(orgTimezone == null ? "UTC" : orgTimezone);
        } catch (Exception e) {
            zone = ZoneId.of("UTC");
        }
        LocalTime now = ZonedDateTime.now(zone).toLocalTime();
        // Email blocked 22:00–09:00 org-local (spec §7).
        return !(now.isAfter(LocalTime.of(22, 0)) || now.isBefore(LocalTime.of(9, 0)));
    }
}

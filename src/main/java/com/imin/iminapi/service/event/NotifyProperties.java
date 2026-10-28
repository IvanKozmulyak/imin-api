package com.imin.iminapi.service.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Notify-me re-arm limits ({@code imin.notify.*}), read per sweep. The buyer-site hint and the
 * {@code notify-release-repeat} email say "at most once every 6 hours": change them with the gap.
 */
@ConfigurationProperties(prefix = "imin.notify")
public class NotifyProperties {

    public static final Duration DEFAULT_REARM_MIN_GAP = Duration.ofHours(6);

    /** A notified row is re-armed only once its last email is at least this old; null or non-positive binds the default. */
    private Duration rearmMinGap = DEFAULT_REARM_MIN_GAP;

    public Duration getRearmMinGap() { return rearmMinGap; }

    public void setRearmMinGap(Duration rearmMinGap) {
        this.rearmMinGap = rearmMinGap == null || rearmMinGap.isZero() || rearmMinGap.isNegative()
                ? DEFAULT_REARM_MIN_GAP : rearmMinGap;
    }
}

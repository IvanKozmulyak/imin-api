package com.imin.iminapi.push;

import java.util.Map;

/**
 * One push, addressed to one device.
 *
 * @param to   an {@code ExponentPushToken[…]} value.
 * @param data payload the app reads to deep-link. Keep it small — APNs caps the
 *             whole notification at 4 KB and Expo rejects oversized payloads
 *             for the entire batch, not just the offending message.
 */
public record PushMessage(String to, String title, String body, String channelId,
                          Map<String, Object> data) {

    /**
     * Android notification channels are created <b>by the app binary</b>, not by
     * the server. A channel the launch cohort never created is a notification
     * they never see; sending a second notification type down the drop-alerts
     * channel means it is mislabelled in the user's own system settings and is
     * silenced along with drop alerts. So the taxonomy is declared here, in full,
     * before v1.0.0 ships — the app creates all four at startup and later types
     * cost nothing.
     */
    public static final String CHANNEL_DROP_ALERTS = "drop-alerts";
    public static final String CHANNEL_TICKETS = "tickets";
    public static final String CHANNEL_REMINDERS = "reminders";
    public static final String CHANNEL_ORGANIZER_UPDATES = "organizer-updates";
}

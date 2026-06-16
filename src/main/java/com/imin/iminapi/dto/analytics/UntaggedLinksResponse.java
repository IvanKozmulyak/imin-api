package com.imin.iminapi.dto.analytics;

import java.util.List;

/**
 * Top untagged referrer hosts (visits that arrived with no {@code utm_source})
 * with a suggested UTM {@code {source, medium, campaign}} so the organizer can
 * tag the link going forward.
 */
public record UntaggedLinksResponse(List<Link> links) {

    /**
     * {@code sampleUrl} is reserved for a future full-landing-URL capture and is
     * always {@code null} today (the beacon stores only the host).
     */
    public record Link(
            String referrerHost,
            String sampleUrl,
            int visits,
            Suggested suggested) {}

    public record Suggested(String source, String medium, String campaign) {}
}

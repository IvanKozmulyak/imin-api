package com.imin.iminapi.marketing.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GET/POST /api/v1/events/{eventId}/story-kit. {@code blockedReason} is one of NO_POSTER,
 * POSTER_NOT_SUPPORTED, NOT_PUBLIC, ENDED, or null; {@code kit} stays readable when ENDED.
 */
public record StoryKitDto() {

    public record State(
            boolean available,
            String blockedReason,
            String defaultLocale,
            boolean soldOut,
            int visitorsWindowDays,
            Kit kit) {}

    public record Kit(
            UUID id,
            int revision,
            Instant createdAt,
            String locale,
            Link link,
            List<Asset> assets,
            List<Skipped> skipped,
            Caption caption,
            List<String> stale,
            Stats stats) {}

    /** {@code postOn} is a date in the event's zone (YYYY-MM-DD); null for the story and the feed post. */
    public record Asset(String key, int width, int height, String url, String postOn) {}

    public record Skipped(String key, String postOn) {}

    public record Link(String code, String url, Instant createdAt) {}

    public record Caption(String text, boolean aiGenerated, Instant createdAt) {}

    /** Visitors are distinct beacons within the funnel retention; orders and sales are all-time, net of refunds. */
    public record Stats(long visitors, long orders, long netRevenueMinor, String currency) {}
}

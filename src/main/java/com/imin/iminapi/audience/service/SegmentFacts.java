package com.imin.iminapi.audience.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the new segment fields read per member, loaded only for the fields a segment uses.
 * A member without a fan_features row has class {@code none} and no taste or cities.
 * {@code lastOpen} / {@code lastTicketClick} are the latest recorded instants on this org's email campaigns,
 * objected members left out; days since them are counted at {@code asOf}.
 */
public record SegmentFacts(Map<UUID, Features> features, Map<UUID, Set<String>> attended,
                           Map<UUID, Instant> lastOpen, Map<UUID, Instant> lastTicketClick, Instant asOf) {

    public record Features(String guestClass, Set<String> genres, Set<String> cities) {}

    public static final SegmentFacts NONE = new SegmentFacts(Map.of(), Map.of(), Map.of(), Map.of(), Instant.EPOCH);

    String guestClass(UUID membershipId) {
        Features f = membershipId == null ? null : features.get(membershipId);
        return f == null || f.guestClass() == null ? "none" : f.guestClass();
    }

    Set<String> genres(UUID membershipId) {
        Features f = membershipId == null ? null : features.get(membershipId);
        return f == null ? Set.of() : f.genres();
    }

    Set<String> cities(UUID membershipId) {
        Features f = membershipId == null ? null : features.get(membershipId);
        return f == null ? Set.of() : f.cities();
    }

    /** Lower-case event ids the member held a live ticket for. */
    Set<String> attendedEvents(UUID membershipId) {
        return membershipId == null ? Set.of() : attended.getOrDefault(membershipId, Set.of());
    }

    /** Whole elapsed 24-hour periods since the last recorded open, at least 0; {@code Long.MAX_VALUE} when none. */
    long daysSinceOpen(UUID membershipId) {
        return daysSince(membershipId == null ? null : lastOpen.get(membershipId));
    }

    /** As {@link #daysSinceOpen}, for the last click on a campaign event's ticket link. */
    long daysSinceTicketClick(UUID membershipId) {
        return daysSince(membershipId == null ? null : lastTicketClick.get(membershipId));
    }

    // 24-hour periods, not calendar days, so no time zone decides where a day ends.
    private long daysSince(Instant at) {
        if (at == null) return Long.MAX_VALUE;
        return Math.max(0, Duration.between(at, asOf).toDays());
    }
}

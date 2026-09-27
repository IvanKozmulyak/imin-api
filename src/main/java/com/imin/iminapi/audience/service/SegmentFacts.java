package com.imin.iminapi.audience.service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the new segment fields read per member, loaded only for the fields a segment uses.
 * A member without a fan_features row has class {@code none} and no taste or cities.
 */
public record SegmentFacts(Map<UUID, Features> features, Map<UUID, Set<String>> attended) {

    public record Features(String guestClass, Set<String> genres, Set<String> cities) {}

    public static final SegmentFacts NONE = new SegmentFacts(Map.of(), Map.of());

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
}

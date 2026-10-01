package com.imin.iminapi.predictor.calendar;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ligue 1 clubs by city ({@code EventNormalization.cityKey} form), as football-data.org team ids.
 * Ids copied from the 2026-27 FL1 answer recorded on 2026-10-01.
 */
public final class FootballClubs {

    // ponytail: static map of the current season; a promoted club has no city until added (the sync WARNs per unmapped id)
    static final Map<String, List<Integer>> BY_CITY = Map.ofEntries(
            Map.entry("paris", List.of(524, 1045)),   // PSG, Paris FC
            Map.entry("marseille", List.of(516)),
            Map.entry("lyon", List.of(523)),
            Map.entry("lille", List.of(521)),
            Map.entry("lens", List.of(546)),
            Map.entry("rennes", List.of(529)),
            Map.entry("strasbourg", List.of(576)),
            Map.entry("toulouse", List.of(511)),
            Map.entry("nice", List.of(522)),
            Map.entry("monaco", List.of(548)),
            Map.entry("brest", List.of(512)),
            Map.entry("lorient", List.of(525)),
            Map.entry("le havre", List.of(533)),
            Map.entry("le mans", List.of(535)),
            Map.entry("angers", List.of(532)),
            Map.entry("auxerre", List.of(519)),
            Map.entry("troyes", List.of(531)));

    private static final Set<Integer> ALL = all();

    private FootballClubs() {}

    /** Team ids of the city's clubs; empty for a city without a Ligue 1 club or a null key. */
    public static Set<Integer> of(String cityKey) {
        if (cityKey == null) return Set.of();
        return Set.copyOf(BY_CITY.getOrDefault(cityKey, List.of()));
    }

    static boolean mapped(int teamId) {
        return ALL.contains(teamId);
    }

    private static Set<Integer> all() {
        Set<Integer> out = new HashSet<>();
        BY_CITY.values().forEach(out::addAll);
        return Set.copyOf(out);
    }
}

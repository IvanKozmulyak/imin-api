package com.imin.iminapi.audienceplan.dto;

import java.time.Instant;
import java.util.List;

/**
 * The new people a genre can reach around a city, from public open data and sourced research rates only. Holds no
 * personal data and depends only on (genre, city). A null size is unknown, never 0.
 *
 * @param genre     one of the 8 genre bucket keys
 * @param cityKey   the requested city key
 * @param catchment null when the city has no stored centre or no known town is within the radius
 * @param research  the state of the LLM research for this (genre, city); never null
 */
public record AudiencePortraitResponse(String genre, String cityKey, PortraitCatchment catchment,
                                       List<NewPeopleGroup> groups, PortraitResearchStatus research,
                                       Versions versions) {

    /** {@code scope} names the towns the sizes cover: {@code fr_catchment} = the French part of the area. */
    public record PortraitCatchment(int radiusKm, String scope, List<PortraitTown> towns) {}

    /** {@code inScope} is true for the towns whose open data the sizes sum (French towns only). */
    public record PortraitTown(String cityKey, String name, String country, int kmStraight, boolean inScope) {}

    /**
     * One group of people outside the organizer's list.
     *
     * @param key      genre_first | regulars | students | research_1..research_4
     * @param origin   open_data | research (named and described by an LLM from web search; size from open data)
     * @param kind     audience = a genre audience, size a low-high range of reachable people; context = a whole
     *                 population headcount of the catchment (students: all enrolled, not genre-filtered), size
     *                 low == high, never reachable people and never summed with an audience group
     * @param scope    fr_catchment
     * @param cityKeys the towns the size sums
     * @param size     null when any input is unknown; for a research group, the open-data population it belongs
     *                 to (kind context), never the group's own size and never a number from the model
     * @param method   how the size is computed, e.g. electronic_first, no_genre_share_rate, mesr_students, none
     * @param research the LLM text of a research group; null for open_data
     */
    public record NewPeopleGroup(String key, String origin, String kind, String scope, List<String> cityKeys,
                                 SizeRange size, String method, List<PortraitSource> sources,
                                 PortraitResearchGroup research) {}

    /**
     * What the model wrote about a research group. {@code confidence} is {@code cited} when every source URL came
     * from this run's web search, else {@code assumed} (unverified URLs are dropped).
     *
     * @param basis        genre_first | regulars | students | none: the open-data population that sizes it
     * @param aiDisclosure the machine-readable AI marker, always set: this text is model-written
     */
    public record PortraitResearchGroup(String label, String description, String basis, String confidence,
                                        String aiDisclosure, Instant generatedAt) {}

    /**
     * @param status    none (never requested) | pending (requested, no answer yet) | ready | empty (no usable answer)
     * @param stale     true once the research is past its 90-day expiry and waits for the weekly refresh
     * @param reviewedBy who reviewed the research by hand, null when nobody has
     */
    public record PortraitResearchStatus(String status, Instant generatedAt, Instant expiresAt, boolean stale,
                                         String reviewedBy) {}

    public record SizeRange(int low, int high) {}

    /**
     * Where one input comes from. Census and student rows carry dataset, licence, url and {@code updatedAt}; research
     * rates carry the study {@code label} and {@code period} (year), and a {@code note} when derived; a web page a
     * research group cites has input {@code web}, its title as label and its url.
     */
    public record PortraitSource(String input, String cityKey, String dataset, String label, String period,
                                 String licence, String url, String note, Instant updatedAt, boolean stale) {}

    public record Versions(int priors) {}
}

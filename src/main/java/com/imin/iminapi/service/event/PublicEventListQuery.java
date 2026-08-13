package com.imin.iminapi.service.event;

import java.time.Instant;
import java.util.List;

/**
 * One listing query.
 *
 * <p>{@code genres} and {@code types} are LISTS because the buyer feed lets a
 * visitor tick several formats or several genres at once; each list is an OR
 * within its own group and an AND between groups ("Rave or Club, and Techno").
 * An empty or null list means "no filter on this facet", never "match nothing".
 *
 * <p>They stay repeated scalar query params on the wire ({@code ?genre=a&genre=b}),
 * so every single-value link ever handed out keeps working unchanged — it is
 * simply a list of one.
 */
public record PublicEventListQuery(
        Instant from,
        Instant to,
        List<String> genres,
        List<String> types,
        String city,
        String country,
        String orgSlug,
        String q,
        boolean onSaleOnly,
        boolean includeOngoing,
        /** Keep only events whose cheapest purchasable tier is €0 (see EventRepository.findPublicListing). */
        boolean freeOnly,
        int page,
        int pageSize
) {}

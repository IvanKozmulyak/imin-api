package com.imin.iminapi.service.event;

import java.time.Instant;

public record PublicEventListQuery(
        Instant from,
        Instant to,
        String genre,
        String type,
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

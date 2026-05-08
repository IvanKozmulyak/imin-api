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
        int page,
        int pageSize
) {}

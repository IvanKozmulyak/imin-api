package com.imin.iminapi.marketing.dto;

import java.util.List;

/**
 * Response for {@code POST /api/v1/marketing/campaigns/{id}/subject-variants}.
 *
 * <p>{@code variants} is ALWAYS exactly three non-empty, distinct subject-line alternates.
 * The service guarantees the count even when the model returns fewer (it pads with
 * deterministic, event-grounded fallbacks), so the composer can always render three chips.
 */
public record SubjectVariantsResponse(List<String> variants) {}

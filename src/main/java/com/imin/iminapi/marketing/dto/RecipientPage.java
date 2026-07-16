package com.imin.iminapi.marketing.dto;

import java.util.List;

/**
 * One page of a campaign's recipient log.
 *
 * <p>{@code items}/{@code page}/{@code size} are the original (pre-existing) shape and are
 * unchanged. {@code total} and {@code counts} are additive:
 * <ul>
 *   <li>{@code total} — real SQL count of the rows matching the ACTIVE filter
 *       ({@code status} + {@code engagement}), so the client can page the current subset
 *       honestly instead of guessing from a page-scoped tally.</li>
 *   <li>{@code counts} — whole-log aggregates for the filter chips, unaffected by the
 *       active filter or the current page. When no filter is applied,
 *       {@code total == counts.total()}.</li>
 * </ul>
 */
public record RecipientPage(List<RecipientDto> items, int page, int size,
                            long total, RecipientCounts counts) {}

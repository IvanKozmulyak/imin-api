package com.imin.iminapi.dto.publicapi;

/**
 * One city chip on the buyer's {@code /cities} page.
 *
 * <p>{@code eventCount} is how many events the listing would return for this
 * exact {@code (city, country)} pair under the same eligibility predicate the
 * feed uses — published, PUBLIC, not deleted, not DRAFT, not CANCELLED. It is a
 * real count off the same rows, never an estimate.
 */
public record PublicCityItem(String city, String country, long eventCount) {}

package com.imin.iminapi.dto.publicapi;

/**
 * One city chip on the buyer's {@code /cities} page.
 *
 * <p>One chip per normalised city key (V82), so {@code Metz}, {@code METZ} and {@code Metz }
 * are a single row. {@code city} is the display spelling most of that key's events actually
 * use — never a case-folded or title-cased invention, because that breaks real names like
 * {@code 's-Hertogenbosch}.
 *
 * <p>{@code country} is the ISO-3166 alpha-2 code the key's events agree on, or {@code null}
 * when none of them carries one. It is never inferred from a sibling row. When a key carries
 * two different countries — Paris/FR and Paris/US — it is NOT merged: it emits one chip per
 * country (plus an unknown-country chip if some events have none), and the frontend must send
 * {@code &country=} alongside {@code ?city=} for those chips' counts to hold.
 *
 * <p>{@code eventCount} is how many events the listing returns for this chip's own filter,
 * under the same eligibility predicate the feed uses — published, PUBLIC, not deleted, not
 * DRAFT, not CANCELLED. Both sides key off {@code venue_city_key}, so it is a real count off
 * the same rows and can never disagree with the page behind the chip.
 */
public record PublicCityItem(String city, String country, long eventCount) {}

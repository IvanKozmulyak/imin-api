package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.publicapi.PublicEventListItem;
import com.imin.iminapi.dto.publicapi.PublicEventResponse;
import com.imin.iminapi.dto.publicapi.PublicOrganizationDto;
import com.imin.iminapi.dto.publicapi.PublicTierDto;
import com.imin.iminapi.marketing.repository.MetaPixelConnectionRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.stripe.StripeProperties;
import com.imin.iminapi.util.EventNormalization;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class PublicEventService {

    private final EventRepository eventRepository;
    private final TicketTierRepository tierRepository;
    private final OrganizationRepository organizationRepository;
    private final PublicListingProperties listingProperties;
    private final Clock clock;
    private final MetaPixelConnectionRepository metaPixelConnections;
    private final StripeProperties stripeProperties;

    public PublicEventService(EventRepository eventRepository,
                               TicketTierRepository tierRepository,
                               OrganizationRepository organizationRepository,
                               PublicListingProperties listingProperties,
                               Clock clock,
                               MetaPixelConnectionRepository metaPixelConnections,
                               StripeProperties stripeProperties) {
        this.eventRepository = eventRepository;
        this.tierRepository = tierRepository;
        this.organizationRepository = organizationRepository;
        this.listingProperties = listingProperties;
        this.clock = clock;
        this.metaPixelConnections = metaPixelConnections;
        this.stripeProperties = stripeProperties;
    }

    @Transactional(readOnly = true)
    public PublicEventResponse get(UUID id) {
        return get(id, false);
    }

    @Transactional(readOnly = true)
    public PublicEventResponse get(UUID id, boolean includeUnavailable) {
        Event event = eventRepository.findPublic(id)
                .orElseThrow(() -> ApiException.notFound("Event"));

        // Use "Event not found" so we don't leak the org's existence (FK guarantees this branch shouldn't fire).
        Organization org = organizationRepository.findById(event.getOrgId())
                .orElseThrow(() -> ApiException.notFound("Event"));

        Instant now = clock.instant();

        // Default behavior (includeUnavailable=false): only currently-on-sale tiers appear.
        // Disabled, not-yet-opened, closed, sold-out, and tiers under non-LIVE events are
        // hidden — buyers shouldn't see anything they can't purchase.
        //
        // includeUnavailable=true: keep enabled tiers regardless of onSale so the FE can
        // render "Sold Out" / "Sale Ended" / "Coming soon" greyed-out rows. Event-level
        // filtering (draft/private/deleted) is still enforced by findPublic above.
        var stream = tierRepository
                .findByEventIdAndEnabledTrueOrderBySortOrderAsc(id)
                .stream()
                .map(t -> PublicTierDto.from(t, event, now));
        if (!includeUnavailable) {
            stream = stream.filter(PublicTierDto::onSale);
        }
        List<PublicTierDto> tiers = stream.toList();

        String metaPixelId = resolveMetaPixelId(event.getOrgId(), event.getId());
        return PublicEventResponse.from(event, org, tiers, metaPixelId);
    }

    /**
     * Resolves the pixel that the buyer-facing pages should fire: an event-scoped
     * override first, then the org-wide default, and only if the connection is
     * active. Public so {@code PublicOrderController} fires the Purchase pixel off
     * exactly the same resolution as the event page's ViewContent.
     */
    public String resolveMetaPixelId(java.util.UUID orgId, java.util.UUID eventId) {
        return metaPixelConnections.findByOrgIdAndEventId(orgId, eventId)
                .or(() -> metaPixelConnections.findByOrgIdAndEventIdIsNull(orgId))
                .filter(c -> "active".equals(c.getStatus()))
                .map(com.imin.iminapi.marketing.model.MetaPixelConnection::getPixelId)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public PageResponse<PublicEventListItem> list(PublicEventListQuery query) {
        // 1. Validate / normalize
        Map<String, String> errors = new LinkedHashMap<>();
        if (query.q() != null && !query.q().isBlank() && query.q().length() < 2) {
            errors.put("q", "min 2 chars");
        }
        if (query.country() != null && !query.country().isBlank() && query.country().length() != 2) {
            errors.put("country", "must be ISO-3166 alpha-2");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "Invalid filter", errors);
        }
        int page = Math.max(1, query.page());
        int pageSize = Math.min(100, Math.max(1, query.pageSize()));
        String country = query.country() != null && !query.country().isBlank()
                ? query.country().toUpperCase(Locale.ROOT) : null;

        // 2. Resolve orgSlug -> orgId (empty result if slug not found)
        UUID orgId = null;
        if (query.orgSlug() != null && !query.orgSlug().isBlank()) {
            var maybe = organizationRepository.findBySlug(query.orgSlug());
            if (maybe.isEmpty()) {
                return new PageResponse<>(List.of(), 0, page, pageSize);
            }
            orgId = maybe.get().getId();
        }

        // 3. Query. The city and genre filters both run on their normalised keys (V82), so
        //    "?city=METZ", "?city=metz" and "?city=%20Metz" are the same query — as are
        //    "?genre=Techno" and "?genre=techno" — and they are the same queries the facets
        //    counted. Exact match, not the old substring LIKE: a chip that says "Metz (3)"
        //    must not open a page that also lists Metzingen.
        String rawCity = nullIfBlank(query.city());
        String cityKey = rawCity == null ? null : nullIfBlank(EventNormalization.cityKey(rawCity));
        // Both facets are multi-select: ?genre=a&genre=b is "a OR b", and the two
        // groups AND together. Genres normalise to their keys first, exactly as the
        // single-value filter always did, so a chip still returns the nights it counted.
        List<String> genreKeys = distinctNonBlank(query.genres(), EventNormalization::genreKey);
        List<String> types = distinctNonBlank(query.types(), t -> t);
        Page<Event> result = eventRepository.findPublicListing(
                query.from(), query.to(),
                bindable(genreKeys), genreKeys.size(),
                bindable(types), types.size(),
                cityKey, country, nullIfBlank(query.q()),
                orgId, query.onSaleOnly(), query.includeOngoing(), query.freeOnly(), clock.instant(),
                PageRequest.of(page - 1, pageSize));

        // 4. Batch-load the page's enabled tiers + orgs. One tier query, no N+1:
        //    priceFromMinor / soldOut / lowStock are all derived from these rows in Java
        //    because purchasability depends on the event too (see TierAvailability).
        List<UUID> eventIds = result.getContent().stream().map(Event::getId).toList();
        Map<UUID, List<TicketTier>> tiersByEvent = eventIds.isEmpty()
                ? Map.of()
                : tierRepository.findByEventIdInAndEnabledTrue(eventIds).stream()
                        .collect(Collectors.groupingBy(TicketTier::getEventId));

        Set<UUID> orgIds = result.getContent().stream().map(Event::getOrgId).collect(Collectors.toSet());
        Map<UUID, Organization> orgsById = orgIds.isEmpty()
                ? Map.of()
                : StreamSupport.stream(organizationRepository.findAllById(orgIds).spliterator(), false)
                        .collect(Collectors.toMap(Organization::getId, o -> o));

        Instant now = clock.instant();
        return PageResponse.from(result, e -> toListItem(
                e, tiersByEvent.getOrDefault(e.getId(), List.of()), orgsById.get(e.getOrgId()),
                now, listingProperties.getLowStockThreshold(),
                stripeProperties.getApplicationFeeBps(),
                stripeProperties.getApplicationFeeFixedMinor()));
    }

    /**
     * City facets with per-city event counts, off the listing's own eligibility predicate.
     *
     * <p>One chip per {@code venue_city_key} (V82), which is the same column {@code ?city=}
     * filters on — so a chip's count is exactly what tapping it returns. {@code Metz/FR},
     * {@code Metz/null} and {@code METZ/''} were three chips for one place; they are now one,
     * labelled with the <b>most common display spelling</b> (ties: fewest capitals, then
     * alphabetical — "Metz" over "METZ") and carrying the one country its events agree on.
     *
     * <p><b>Ambiguous keys are NOT merged.</b> If a key carries two or more different known
     * countries — Paris/FR and Paris/US — those are two cities that happen to share a name, and
     * collapsing them would be a lie. Such a key keeps one chip per stored country value,
     * including an unknown-country chip if some of its events have no country at all; the
     * frontend must add {@code &country=} to those chips' links for their counts to hold.
     * No country is ever inferred from a sibling row — an unknown country stays {@code null}.
     *
     * <p><b>Upcoming only.</b> The count is bounded by {@code clock.instant()} the same way the
     * feed behind the chip is (see {@link EventRepository#findPublicCityCounts(Instant)}): every
     * real client sends {@code from=now&includeOngoing=true}, so a count over all time promised
     * nights that no feed could show. A city with nothing left ahead of it returns no chip.
     */
    @Transactional(readOnly = true)
    public List<com.imin.iminapi.dto.publicapi.PublicCityItem> listCities() {
        // row = [cityKey, displaySpelling, country, count]
        Map<String, List<Object[]>> byKey = new LinkedHashMap<>();
        for (Object[] row : eventRepository.findPublicCityCounts(clock.instant())) {
            byKey.computeIfAbsent((String) row[0], k -> new java.util.ArrayList<>()).add(row);
        }

        List<com.imin.iminapi.dto.publicapi.PublicCityItem> out = new java.util.ArrayList<>();
        for (List<Object[]> rows : byKey.values()) {
            Set<String> countries = rows.stream()
                    .map(r -> countryOf(r))
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toCollection(java.util.TreeSet::new));
            if (countries.size() <= 1) {
                out.add(chip(rows, countries.isEmpty() ? null : countries.iterator().next()));
            } else {
                // Same name, genuinely different countries: keep them apart, unknown included.
                Map<String, List<Object[]>> byCountry = new LinkedHashMap<>();
                for (Object[] r : rows) {
                    String c = countryOf(r);
                    byCountry.computeIfAbsent(c == null ? "" : c, k -> new java.util.ArrayList<>()).add(r);
                }
                byCountry.forEach((c, bucket) -> out.add(chip(bucket, c.isEmpty() ? null : c)));
            }
        }

        // Alphabetical by label, ignoring case so "METZ" doesn't sort into a separate block;
        // then by country (unknown last) so an ambiguous key's chips have a stable order.
        out.sort(java.util.Comparator
                .comparing(com.imin.iminapi.dto.publicapi.PublicCityItem::city, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(com.imin.iminapi.dto.publicapi.PublicCityItem::country,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(com.imin.iminapi.dto.publicapi.PublicCityItem::city));
        return List.copyOf(out);
    }

    /** Country of a facet row, blank folded to {@code null} and upper-cased — {@code ''} is not a country. */
    private static String countryOf(Object[] row) {
        String raw = (String) row[2];
        return raw == null || raw.isBlank() ? null : raw.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * One chip out of the rows sharing a key: count is their sum, label is the spelling the most
     * events actually use. Ties break toward the least-shouted spelling and then alphabetically,
     * so the label is deterministic instead of "whichever row the planner returned first".
     */
    private static com.imin.iminapi.dto.publicapi.PublicCityItem chip(List<Object[]> rows, String country) {
        Map<String, Long> bySpelling = new LinkedHashMap<>();
        long total = 0;
        for (Object[] r : rows) {
            long n = ((Number) r[3]).longValue();
            total += n;
            bySpelling.merge((String) r[1], n, Long::sum);
        }
        return new com.imin.iminapi.dto.publicapi.PublicCityItem(label(bySpelling), country, total);
    }

    /**
     * The label for a merged facet key: the spelling the most events actually use. Ties break
     * toward the least-shouted spelling ("Metz" over "METZ", "Techno" over "TECHNO") and then
     * alphabetically, so the label is deterministic instead of "whichever row the planner
     * returned first". Shared by the city and genre facets — one rule, one implementation.
     */
    private static String label(Map<String, Long> bySpelling) {
        return bySpelling.entrySet().stream()
                .max(java.util.Comparator
                        .comparingLong(Map.Entry<String, Long>::getValue)
                        .thenComparing(e -> -capitals(e.getKey()))
                        .thenComparing(java.util.Map.Entry::getKey, java.util.Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private static long capitals(String s) {
        return s.chars().filter(Character::isUpperCase).count();
    }

    /**
     * Genre facet — one label per {@code genre_key} (V82), which is the same column
     * {@code ?genre=} filters on, so tapping a chip returns exactly the nights it counted.
     *
     * <p>{@code Techno} and {@code techno} used to be two identical-looking chips over two
     * disjoint slices of one genre. They are now one, labelled with the <b>most common display
     * spelling</b> (ties: fewest capitals, then alphabetical — the city rule, see
     * {@link #label(Map)}). The label is a real stored spelling, never a case-folded or
     * title-cased invention: both frontends print it verbatim, and {@code imin-webapp}'s wizard
     * matches it against a closed Title-Case option list.
     *
     * <p>Sorted alphabetically ignoring case, so "Techno" and "afro house" interleave the way a
     * reader expects rather than splitting into upper- and lower-case blocks.
     *
     * <p><b>Upcoming only</b>, on the same clock and for the same reason as {@link #listCities()}:
     * a chip is a promise that tapping it shows something, and a genre whose nights have all
     * passed opened an empty feed.
     */
    @Transactional(readOnly = true)
    public List<String> listGenres() {
        // row = [genreKey, displaySpelling, count]
        Map<String, Map<String, Long>> byKey = new LinkedHashMap<>();
        for (Object[] row : eventRepository.findPublicGenreCounts(clock.instant())) {
            byKey.computeIfAbsent((String) row[0], k -> new LinkedHashMap<>())
                    .merge((String) row[1], ((Number) row[2]).longValue(), Long::sum);
        }
        return byKey.values().stream()
                .map(PublicEventService::label)
                .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(java.util.Comparator.naturalOrder()))
                .toList();
    }

    /**
     * Builds one card. {@code enabledTiers} are ALL enabled tiers of the event (never null).
     *
     * <p>{@code soldOut}/{@code lowStock} keep their original rollup semantics: the
     * per-tier remaining is left SIGNED so an oversold tier can't mask an empty one
     * ({@code soldOut} = largest per-tier remaining {@code <= 0}), and the total is
     * clamped at 0 only after summing.
     *
     * <p>{@code priceFromMinor} is the min price over <em>purchasable</em> tiers, made
     * fee-inclusive for a single ticket, so the card's "FROM €x" is a number the buyer
     * can actually pay. Free tiers stay 0 (the fee is waived on €0, matching QuoteService).
     * Null when nothing is purchasable — sold out, not yet on sale, or sales ended.
     */
    private static PublicEventListItem toListItem(Event e, List<TicketTier> enabledTiers, Organization org,
                                                  Instant now, int lowStockThreshold,
                                                  int feeBps, int feeFixedMinor) {
        long totalRemaining = 0L;
        long maxRemaining = Long.MIN_VALUE;
        int minPurchasablePrice = Integer.MAX_VALUE;
        for (TicketTier t : enabledTiers) {
            long r = (long) t.getQuantity() - t.getReserved() - t.getSold();
            totalRemaining += r;
            maxRemaining = Math.max(maxRemaining, r);
            if (TierAvailability.isPurchasable(e, t, now)) {
                minPurchasablePrice = Math.min(minPurchasablePrice, t.getPriceMinor());
            }
        }

        // No enabled tiers => event cannot be "sold out".
        boolean hasEnabledTiers = !enabledTiers.isEmpty();
        boolean soldOut = hasEnabledTiers && maxRemaining <= 0L;
        boolean lowStock = hasEnabledTiers && !soldOut
                && Math.max(0L, totalRemaining) <= lowStockThreshold;

        Integer priceFromMinor = null;
        if (minPurchasablePrice != Integer.MAX_VALUE) {
            priceFromMinor = minPurchasablePrice == 0
                    ? 0
                    : (int) (minPurchasablePrice
                            + QuoteService.computeFee(minPurchasablePrice, 1, feeBps, feeFixedMinor));
        }

        return new PublicEventListItem(
                e.getId(), e.getSlug(), e.getName(), e.getStatus().wireValue(), e.getPublishedAt(),
                e.getGenre(), e.getType(),
                e.getStartsAt(), e.getEndsAt(), e.getTimezone(),
                e.getVenueName(), e.getVenueCity(), e.getVenueCountry(), e.getPosterUrl(), e.getCurrency(),
                priceFromMinor, soldOut, lowStock,
                new PublicOrganizationDto(org.getName(), org.getSlug()));
    }

    private static String nullIfBlank(String s) { return s == null || s.isBlank() ? null : s; }

    /**
     * Normalise a multi-select facet: drop blanks, apply the facet's key function,
     * de-duplicate, preserve order.
     *
     * <p>De-duplication matters beyond tidiness — {@code ?genre=Techno&genre=techno}
     * is one key twice, and binding it twice would widen the IN list for no reason
     * on every page of a shared link.
     */
    private static List<String> distinctNonBlank(List<String> raw, UnaryOperator<String> key) {
        if (raw == null || raw.isEmpty()) return List.of();
        return raw.stream()
                .map(PublicEventService::nullIfBlank)
                .filter(Objects::nonNull)
                .map(key)
                .map(PublicEventService::nullIfBlank)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * The collection actually handed to JPA. Never empty: H2 and Postgres render an
     * empty {@code IN ()} differently, and the count guard beside it already makes
     * the contents irrelevant when the facet is off.
     *
     * <p><b>The placeholder must be a legal string, not merely an impossible key.</b>
     * The first version of this used {@code "\u0000"} — unmatchable, and accepted by
     * H2, so the whole suite passed. Postgres rejects a NUL byte in text outright
     * ({@code invalid byte sequence for encoding "UTF8": 0x00}), which took the
     * entire public listing to 400 in production the moment it deployed. Same class
     * of H2-passes-Postgres-fails divergence this repo already documents for
     * {@code lower(bytea)} and for partial indexes; the lesson is that "impossible
     * value" and "valid value" are different requirements and this needs both.
     */
    private static Collection<String> bindable(List<String> values) {
        return values.isEmpty() ? NO_FACET : values;
    }

    /**
     * Bound when a facet is off. Ordinary text, so every engine accepts it, and not
     * a value any normalised key can take: {@code genreKey} lowercases and collapses
     * whitespace, so a key can never contain a space run or upper case.
     */
    private static final Collection<String> NO_FACET = List.of("\u0020__no facet__\u0020");
}

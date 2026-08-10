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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    private String resolveMetaPixelId(java.util.UUID orgId, java.util.UUID eventId) {
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

        // 3. Query
        Page<Event> result = eventRepository.findPublicListing(
                query.from(), query.to(),
                nullIfBlank(query.genre()), nullIfBlank(query.type()),
                nullIfBlank(query.city()), country, nullIfBlank(query.q()),
                orgId, query.onSaleOnly(), query.includeOngoing(), clock.instant(),
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

    @Transactional(readOnly = true)
    public List<com.imin.iminapi.dto.publicapi.PublicCityItem> listCities() {
        return eventRepository.findDistinctPublicCities().stream()
                .map(row -> new com.imin.iminapi.dto.publicapi.PublicCityItem(
                        (String) row[0], (String) row[1]))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> listGenres() {
        return eventRepository.findDistinctPublicGenres();
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
}

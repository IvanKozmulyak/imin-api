package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.publicapi.PublicEventListItem;
import com.imin.iminapi.dto.publicapi.PublicEventResponse;
import com.imin.iminapi.dto.publicapi.PublicOrganizationDto;
import com.imin.iminapi.dto.publicapi.PublicTierDto;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
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
    private final Clock clock;

    public PublicEventService(EventRepository eventRepository,
                               TicketTierRepository tierRepository,
                               OrganizationRepository organizationRepository,
                               Clock clock) {
        this.eventRepository = eventRepository;
        this.tierRepository = tierRepository;
        this.organizationRepository = organizationRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PublicEventResponse get(UUID id) {
        Event event = eventRepository.findPublic(id)
                .orElseThrow(() -> ApiException.notFound("Event"));

        // Use "Event not found" so we don't leak the org's existence (FK guarantees this branch shouldn't fire).
        Organization org = organizationRepository.findById(event.getOrgId())
                .orElseThrow(() -> ApiException.notFound("Event"));

        Instant now = clock.instant();

        // Public page only shows currently-on-sale tiers. Disabled, not-yet-opened,
        // closed, sold-out, and tiers under non-LIVE events are all hidden — buyers
        // shouldn't see (or attempt to buy) anything they can't actually purchase.
        List<PublicTierDto> tiers = tierRepository
                .findByEventIdAndEnabledTrueOrderBySortOrderAsc(id)
                .stream()
                .map(t -> PublicTierDto.from(t, event, now))
                .filter(PublicTierDto::onSale)
                .toList();

        return PublicEventResponse.from(event, org, tiers);
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
                orgId, query.onSaleOnly(), clock.instant(),
                PageRequest.of(page - 1, pageSize));

        // 4. Batch-load priceFromMinor + orgs
        List<UUID> eventIds = result.getContent().stream().map(Event::getId).toList();
        Map<UUID, Integer> priceByEvent = eventIds.isEmpty()
                ? Map.of()
                : tierRepository.findMinEnabledPriceByEventIds(eventIds).stream()
                        .collect(Collectors.toMap(
                                row -> (UUID) row[0],
                                row -> ((Number) row[1]).intValue()));

        Set<UUID> orgIds = result.getContent().stream().map(Event::getOrgId).collect(Collectors.toSet());
        Map<UUID, Organization> orgsById = orgIds.isEmpty()
                ? Map.of()
                : StreamSupport.stream(organizationRepository.findAllById(orgIds).spliterator(), false)
                        .collect(Collectors.toMap(Organization::getId, o -> o));

        return PageResponse.from(result, e -> toListItem(e, priceByEvent.get(e.getId()), orgsById.get(e.getOrgId())));
    }

    @Transactional(readOnly = true)
    public List<com.imin.iminapi.dto.publicapi.PublicCityItem> listCities() {
        return eventRepository.findDistinctPublicCities().stream()
                .map(row -> new com.imin.iminapi.dto.publicapi.PublicCityItem(
                        (String) row[0], (String) row[1]))
                .toList();
    }

    private static PublicEventListItem toListItem(Event e, Integer priceFromMinor, Organization org) {
        return new PublicEventListItem(
                e.getId(), e.getSlug(), e.getName(), e.getStatus().wireValue(), e.getPublishedAt(),
                e.getGenre(), e.getType(),
                e.getStartsAt(), e.getEndsAt(), e.getTimezone(),
                e.getVenueCity(), e.getVenueCountry(), e.getPosterUrl(), e.getCurrency(),
                priceFromMinor,
                new PublicOrganizationDto(org.getName(), org.getSlug()));
    }

    private static String nullIfBlank(String s) { return s == null || s.isBlank() ? null : s; }
}

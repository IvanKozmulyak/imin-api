package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.publicapi.PublicEventResponse;
import com.imin.iminapi.dto.publicapi.PublicOrganizationDto;
import com.imin.iminapi.dto.publicapi.PublicTierDto;
import com.imin.iminapi.dto.publicapi.PublicVenueDto;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Event not found"));

        Organization org = organizationRepository.findById(event.getOrgId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Event not found"));

        Instant now = clock.instant();
        boolean eventOver = event.getStatus() == EventStatus.PAST
                         || event.getStatus() == EventStatus.CANCELLED;

        List<PublicTierDto> tiers = tierRepository
                .findByEventIdAndEnabledTrueOrderBySortOrderAsc(id)
                .stream()
                .map(t -> PublicTierDto.of(
                        t.getId(),
                        t.getName(),
                        t.getKind().wireValue(),
                        t.getPriceMinor(),
                        event.getCurrency(),
                        t.getSaleClosesAt(),
                        t.getSortOrder(),
                        t.getQuantity(),
                        t.getSold(),
                        event.getOnSaleAt(),
                        event.getSaleClosesAt(),
                        eventOver,
                        now))
                .toList();

        return new PublicEventResponse(
                event.getId(),
                event.getSlug(),
                event.getName(),
                event.getStatus().wireValue(),
                event.getPublishedAt(),
                event.getGenre(),
                event.getType(),
                event.getDescription(),
                event.getStartsAt(),
                event.getEndsAt(),
                event.getTimezone(),
                new PublicVenueDto(
                        event.getVenueName(),
                        event.getVenueStreet(),
                        event.getVenueCity(),
                        event.getVenuePostalCode(),
                        event.getVenueCountry()),
                event.getCoverUrl(),
                event.getPosterUrl(),
                event.getVideoUrl(),
                event.getCurrency(),
                event.getOnSaleAt(),
                event.getSaleClosesAt(),
                event.isSquadsEnabled(),
                event.getMinSquadSize(),
                event.getSquadDiscountPct(),
                new PublicOrganizationDto(org.getName(), org.getSlug()),
                tiers);
    }
}

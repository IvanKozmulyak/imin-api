package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.publicapi.PublicEventResponse;
import com.imin.iminapi.dto.publicapi.PublicTierDto;
import com.imin.iminapi.model.Event;
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

        List<PublicTierDto> tiers = tierRepository
                .findByEventIdAndEnabledTrueOrderBySortOrderAsc(id)
                .stream()
                .map(t -> PublicTierDto.from(t, event, now))
                .toList();

        return PublicEventResponse.from(event, org, tiers);
    }
}

package com.imin.iminapi.controller.event;

import com.imin.iminapi.dto.event.TicketTierCreateRequest;
import com.imin.iminapi.dto.event.TicketTierDto;
import com.imin.iminapi.dto.event.TicketTierPatchRequest;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.event.TicketTierService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events/{eventId}/tiers")
public class EventTierController {

    private final TicketTierService tierService;

    public EventTierController(TicketTierService tierService) {
        this.tierService = tierService;
    }

    @PostMapping
    public ResponseEntity<TicketTierDto> create(@CurrentUser AuthPrincipal p,
                                                @PathVariable UUID eventId,
                                                @RequestBody TicketTierCreateRequest body) {
        TicketTierDto dto = tierService.create(p, eventId, body);
        return ResponseEntity
                .created(URI.create("/api/v1/events/" + eventId + "/tiers/" + dto.id()))
                .body(dto);
    }

    @PatchMapping("/{tierId}")
    public TicketTierDto patch(@CurrentUser AuthPrincipal p,
                               @PathVariable UUID eventId,
                               @PathVariable UUID tierId,
                               @RequestBody TicketTierPatchRequest body) {
        return tierService.patch(p, eventId, tierId, body);
    }

    @DeleteMapping("/{tierId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser AuthPrincipal p,
                       @PathVariable UUID eventId,
                       @PathVariable UUID tierId) {
        tierService.delete(p, eventId, tierId);
    }
}

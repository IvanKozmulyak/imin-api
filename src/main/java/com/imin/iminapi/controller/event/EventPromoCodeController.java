package com.imin.iminapi.controller.event;

import com.imin.iminapi.dto.event.PromoCodeCreateRequest;
import com.imin.iminapi.dto.event.PromoCodeDto;
import com.imin.iminapi.dto.event.PromoCodePatchRequest;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.event.PromoCodeService;
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
@RequestMapping("/api/v1/events/{eventId}/promos")
public class EventPromoCodeController {

    private final PromoCodeService service;

    public EventPromoCodeController(PromoCodeService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PromoCodeDto> create(@CurrentUser AuthPrincipal p,
                                               @PathVariable UUID eventId,
                                               @RequestBody PromoCodeCreateRequest body) {
        PromoCodeDto dto = service.create(p, eventId, body);
        return ResponseEntity
                .created(URI.create("/api/v1/events/" + eventId + "/promos/" + dto.id()))
                .body(dto);
    }

    @PatchMapping("/{promoId}")
    public PromoCodeDto patch(@CurrentUser AuthPrincipal p,
                              @PathVariable UUID eventId,
                              @PathVariable UUID promoId,
                              @RequestBody PromoCodePatchRequest body) {
        return service.patch(p, eventId, promoId, body);
    }

    @DeleteMapping("/{promoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser AuthPrincipal p,
                       @PathVariable UUID eventId,
                       @PathVariable UUID promoId) {
        service.delete(p, eventId, promoId);
    }
}

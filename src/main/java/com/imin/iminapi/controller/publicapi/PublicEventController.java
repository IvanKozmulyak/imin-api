package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.dto.publicapi.PublicEventResponse;
import com.imin.iminapi.service.event.PublicEventService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public/events")
public class PublicEventController {

    private final PublicEventService publicEventService;

    public PublicEventController(PublicEventService publicEventService) {
        this.publicEventService = publicEventService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<PublicEventResponse> get(@PathVariable UUID id) {
        PublicEventResponse body = publicEventService.get(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL,
                        "public, s-maxage=60, stale-while-revalidate=30")
                .body(body);
    }
}

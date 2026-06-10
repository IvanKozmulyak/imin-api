package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.publicapi.NotifySubscriptionRequest;
import com.imin.iminapi.dto.publicapi.NotifySubscriptionResponse;
import com.imin.iminapi.dto.publicapi.PublicCityItem;
import com.imin.iminapi.dto.publicapi.PublicEventListItem;
import com.imin.iminapi.dto.publicapi.PublicEventResponse;
import com.imin.iminapi.dto.publicapi.QuoteRequest;
import com.imin.iminapi.dto.publicapi.QuoteResponse;
import com.imin.iminapi.service.event.NotifySubscriptionService;
import com.imin.iminapi.service.event.PublicEventListQuery;
import com.imin.iminapi.service.event.PublicEventService;
import com.imin.iminapi.service.event.QuoteService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public/events")
public class PublicEventController {

    private final PublicEventService publicEventService;
    private final NotifySubscriptionService notifySubscriptionService;
    private final QuoteService quoteService;

    public PublicEventController(PublicEventService publicEventService,
                                 NotifySubscriptionService notifySubscriptionService,
                                 QuoteService quoteService) {
        this.publicEventService = publicEventService;
        this.notifySubscriptionService = notifySubscriptionService;
        this.quoteService = quoteService;
    }

    @GetMapping("/cities")
    public ResponseEntity<List<PublicCityItem>> listCities() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, s-maxage=60, stale-while-revalidate=30")
                .body(publicEventService.listCities());
    }

    @GetMapping("/genres")
    public ResponseEntity<List<String>> listGenres() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, s-maxage=60, stale-while-revalidate=30")
                .body(publicEventService.listGenres());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PublicEventResponse> get(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean includeUnavailable) {
        PublicEventResponse body = publicEventService.get(id, includeUnavailable);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL,
                        "public, s-maxage=60, stale-while-revalidate=30")
                .body(body);
    }

    @PostMapping("/{id}/notify")
    public ResponseEntity<NotifySubscriptionResponse> notify(
            @PathVariable UUID id,
            @RequestBody(required = false) NotifySubscriptionRequest body) {
        NotifySubscriptionResponse response = notifySubscriptionService.subscribe(id, body);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/quote")
    public ResponseEntity<QuoteResponse> quote(
            @PathVariable UUID id,
            @RequestBody(required = false) QuoteRequest body) {
        QuoteResponse response = quoteService.quote(id, body);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<PageResponse<PublicEventListItem>> list(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String orgSlug,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean onSaleOnly,
            @RequestParam(defaultValue = "false") boolean includeOngoing,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                from, to, genre, type, city, country, orgSlug, q, onSaleOnly, includeOngoing, page, pageSize));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, s-maxage=60, stale-while-revalidate=30")
                .body(result);
    }
}

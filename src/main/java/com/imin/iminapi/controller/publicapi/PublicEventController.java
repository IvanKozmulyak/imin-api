package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.publicapi.NotifySubscriptionRequest;
import com.imin.iminapi.dto.publicapi.NotifySubscriptionResponse;
import com.imin.iminapi.dto.publicapi.PublicCityItem;
import com.imin.iminapi.dto.publicapi.PublicEventListItem;
import com.imin.iminapi.dto.publicapi.PublicEventResponse;
import com.imin.iminapi.dto.publicapi.QuoteRequest;
import com.imin.iminapi.dto.publicapi.QuoteResponse;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.event.NotifySubscriptionService;
import com.imin.iminapi.service.event.PublicEventListQuery;
import com.imin.iminapi.service.event.PublicEventService;
import com.imin.iminapi.service.event.QuoteService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final RateLimiter rateLimiter;

    public PublicEventController(PublicEventService publicEventService,
                                 NotifySubscriptionService notifySubscriptionService,
                                 QuoteService quoteService,
                                 RateLimiter rateLimiter) {
        this.publicEventService = publicEventService;
        this.notifySubscriptionService = notifySubscriptionService;
        this.quoteService = quoteService;
        this.rateLimiter = rateLimiter;
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
            @RequestBody(required = false) NotifySubscriptionRequest body,
            HttpServletRequest http) {
        // Throttle the unauthenticated notify-me subscribe per client IP, same shape as the
        // public checkout limiter. Without it a loop can fill notify_subscriptions with fake
        // addresses, and every one of those rows later earns a real send from
        // NotifyReleaseSender — a free spam relay pointed at our sending domain.
        // Keyed on getRemoteAddr(), which forward-headers-strategy=framework resolves
        // from the proxy's own X-Forwarded-For handling — never the raw header.
        rateLimiter.consume("notify-subscribe", "ip:" + http.getRemoteAddr());
        // Consent provenance (V77): who asked, from where, with which client. Captured
        // here because the service has no HTTP context — same split as PublicRecoveryController.
        NotifySubscriptionResponse response = notifySubscriptionService.subscribe(
                id, body, clientIp(http), http.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.ok(response);
    }

    /**
     * Client IP for the consent-provenance record.
     *
     * <p>This used to read the first hop of the raw {@code X-Forwarded-For}
     * header, on the reasoning that an evidence trail is not an authorization
     * input so a spoofable value is harmless. It is not harmless: the value is
     * <b>consent evidence</b>, and evidence the subject of the record can dictate
     * is worth nothing — anyone could have written any address into the trail,
     * including someone else's. {@code getRemoteAddr()} is the resolved address
     * ({@code server.forward-headers-strategy: framework} makes Spring apply the
     * proxy's forwarded headers itself, so this is the real client behind
     * Railway), and it is the same value the rate limiter above trusts.
     */
    private static String clientIp(HttpServletRequest http) {
        return http.getRemoteAddr();
    }

    @PostMapping("/{id}/quote")
    public ResponseEntity<QuoteResponse> quote(
            @PathVariable UUID id,
            @RequestBody(required = false) QuoteRequest body,
            HttpServletRequest http) {
        // Same throttle as the notify sibling above, and for a sharper reason: this
        // endpoint answers 200 with a DIFFERENT reason per promo-code failure mode
        // (unknown / disabled / exhausted), so unmetered it is a promo-code oracle an
        // anonymous caller can grind for free — three DB reads a guess — before
        // spending a found code through the metered `checkout` bucket. Keyed on
        // getRemoteAddr(), which forward-headers-strategy=framework resolves from the
        // proxy's own X-Forwarded-For handling — never the raw header.
        rateLimiter.consume("quote", "ip:" + http.getRemoteAddr());
        QuoteResponse response = quoteService.quote(id, body);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<PageResponse<PublicEventListItem>> list(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            // Repeated params: ?genre=Techno&genre=House, ?type=Rave&type=Club.
            // Spring binds a single occurrence to a one-element list, so every
            // link handed out before multi-select existed still resolves.
            @RequestParam(required = false) List<String> genre,
            @RequestParam(required = false) List<String> type,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String orgSlug,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean onSaleOnly,
            @RequestParam(defaultValue = "false") boolean includeOngoing,
            @RequestParam(defaultValue = "false") boolean freeOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageResponse<PublicEventListItem> result = publicEventService.list(new PublicEventListQuery(
                from, to, genre, type, city, country, orgSlug, q,
                onSaleOnly, includeOngoing, freeOnly, page, pageSize));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, s-maxage=60, stale-while-revalidate=30")
                .body(result);
    }
}

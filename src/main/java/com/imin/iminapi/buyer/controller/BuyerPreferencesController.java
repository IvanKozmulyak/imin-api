package com.imin.iminapi.buyer.controller;

import com.imin.iminapi.buyer.dto.BuyerOrganizerListResponse;
import com.imin.iminapi.buyer.dto.BuyerPreferencesResponse;
import com.imin.iminapi.buyer.security.BuyerPrincipal;
import com.imin.iminapi.buyer.security.CurrentBuyer;
import com.imin.iminapi.buyer.service.BuyerPreferencesService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The preference centre — spec §4.4.
 *
 * <p>{@code GET /buyer/organizers} is disclosure only. There is deliberately no
 * {@code PATCH /buyer/organizers/{orgId}}: re-subscribing to one organizer
 * belongs on that organizer's own footer link, where the proof text is theirs
 * to write.
 */
@RestController
public class BuyerPreferencesController {

    private static final String NO_STORE = "private, no-store";

    private final BuyerPreferencesService service;

    public BuyerPreferencesController(BuyerPreferencesService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/buyer/preferences")
    public ResponseEntity<BuyerPreferencesResponse> read(@CurrentBuyer BuyerPrincipal buyer) {
        return noStore(service.read(buyer.accountId()));
    }

    /**
     * Partial, same reasoning as {@code PATCH /buyer/me}: an absent key means
     * "leave it alone". Taken as a map so a body carrying only
     * {@code organizerUpdates} cannot silently reset {@code eventReminders} to
     * its default.
     */
    @PatchMapping("/api/v1/buyer/preferences")
    public ResponseEntity<BuyerPreferencesResponse> patch(@CurrentBuyer BuyerPrincipal buyer,
                                                          @RequestBody Map<String, Object> body) {
        return noStore(service.update(buyer.accountId(), body));
    }

    /**
     * Answers {@code {items, nextCursor}}, not a bare array — see
     * {@link com.imin.iminapi.buyer.dto.BuyerSavedListResponse}: a top-level
     * array cannot grow a cursor without breaking every shipped app binary.
     */
    @GetMapping("/api/v1/buyer/organizers")
    public ResponseEntity<BuyerOrganizerListResponse> organizers(
            @CurrentBuyer BuyerPrincipal buyer) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(BuyerOrganizerListResponse.of(service.organizers(buyer.accountId())));
    }

    private ResponseEntity<BuyerPreferencesResponse> noStore(BuyerPreferencesResponse body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(body);
    }
}

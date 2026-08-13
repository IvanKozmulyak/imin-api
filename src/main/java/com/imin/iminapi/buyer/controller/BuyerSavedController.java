package com.imin.iminapi.buyer.controller;

import com.imin.iminapi.buyer.dto.BuyerSavedResponse;
import com.imin.iminapi.buyer.security.BuyerPrincipal;
import com.imin.iminapi.buyer.security.CurrentBuyer;
import com.imin.iminapi.buyer.service.BuyerSavedService;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Saved events — PUBLIC_PAGE_API.md §20.4, documented since R1.4 and never
 * built until now. {@code lib/api/buyer.ts} has been calling all four of these
 * and getting 404s, which it reads as <i>signed out</i>; the localStorage
 * fallback in {@code lib/saved-events.ts} is why nobody noticed.
 *
 * <p>{@code Cache-Control: private, no-store} on every response, matching
 * {@link BuyerMeController} — a saved list is buyer-specific.
 */
@RestController
public class BuyerSavedController {

    private static final String NO_STORE = "private, no-store";

    private final BuyerSavedService service;

    public BuyerSavedController(BuyerSavedService service) {
        this.service = service;
    }

    /** The merge body. A null list is rejected; an empty one is a legal no-op. */
    public record MergeRequest(@NotNull List<UUID> eventIds) {}

    @GetMapping("/api/v1/buyer/saved")
    public ResponseEntity<List<BuyerSavedResponse>> list(@CurrentBuyer BuyerPrincipal buyer) {
        return noStore(service.list(buyer.accountId()).stream().map(BuyerSavedResponse::of).toList());
    }

    @PutMapping("/api/v1/buyer/saved/{eventId}")
    public ResponseEntity<Void> save(@CurrentBuyer BuyerPrincipal buyer, @PathVariable UUID eventId) {
        service.save(buyer.accountId(), eventId);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, NO_STORE).build();
    }

    @DeleteMapping("/api/v1/buyer/saved/{eventId}")
    public ResponseEntity<Void> remove(@CurrentBuyer BuyerPrincipal buyer, @PathVariable UUID eventId) {
        service.remove(buyer.accountId(), eventId);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, NO_STORE).build();
    }

    @PostMapping("/api/v1/buyer/saved/merge")
    public ResponseEntity<List<BuyerSavedResponse>> merge(@CurrentBuyer BuyerPrincipal buyer,
                                                          @RequestBody MergeRequest body) {
        List<UUID> ids = body.eventIds() == null ? List.of() : body.eventIds();
        return noStore(service.merge(buyer.accountId(), ids).stream().map(BuyerSavedResponse::of).toList());
    }

    private ResponseEntity<List<BuyerSavedResponse>> noStore(List<BuyerSavedResponse> body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(body);
    }
}

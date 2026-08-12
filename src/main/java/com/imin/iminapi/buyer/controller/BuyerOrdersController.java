package com.imin.iminapi.buyer.controller;

import com.imin.iminapi.buyer.dto.BuyerOrdersResponse;
import com.imin.iminapi.buyer.security.BuyerPrincipal;
import com.imin.iminapi.buyer.security.CurrentBuyer;
import com.imin.iminapi.buyer.service.BuyerOrdersService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/buyer/orders} — every ticket a buyer has, across every
 * organizer (epic §3.4). The endpoint accounts exist for.
 *
 * <p><b>Cross-org by design.</b> Scoped by the account's verified addresses,
 * not by an org — see {@link BuyerOrdersService} for the invariant and for why
 * the {@code verified_at IS NOT NULL} predicate in the query is the security
 * boundary of the whole feature.
 *
 * <p><b>Never log this response body.</b> Every item carries an
 * {@code orderToken}, which is a bearer credential for that order and its
 * tickets. Hence {@code Cache-Control: private, no-store}, the {@code BUYER}
 * role requirement in {@code SecurityConfig}, and the cookie-only auth.
 */
@RestController
public class BuyerOrdersController {

    private static final String NO_STORE = "private, no-store";

    private final BuyerOrdersService service;

    public BuyerOrdersController(BuyerOrdersService service) {
        this.service = service;
    }

    /**
     * @param limit  page size; defaults to 20 and is clamped to 50 rather than
     *               rejected, so a client asking for 1000 gets a page instead of
     *               an error it has to special-case
     * @param cursor opaque {@code nextCursor} from the previous page; absent for
     *               the first
     */
    @GetMapping("/api/v1/buyer/orders")
    public ResponseEntity<BuyerOrdersResponse> orders(
            @CurrentBuyer BuyerPrincipal buyer,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(service.page(buyer.accountId(), limit, cursor));
    }
}

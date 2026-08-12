package com.imin.iminapi.buyer.controller;

import com.imin.iminapi.buyer.dto.BuyerMeResponse;
import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountRepository;
import com.imin.iminapi.buyer.security.BuyerPrincipal;
import com.imin.iminapi.buyer.security.CurrentBuyer;
import com.imin.iminapi.buyer.service.BuyerSessionService;
import com.imin.iminapi.security.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The authenticated buyer's own account.
 *
 * <p><b>This is the first authenticated surface in the platform that is not
 * org-scoped.</b> Every other one derives its tenant from
 * {@code AuthPrincipal.orgId}; this one is scoped by
 * {@link BuyerPrincipal#accountId()} and, once {@code GET /buyer/orders} lands,
 * crosses organizers by design. Do not "fix" that into an org filter.
 *
 * <p>Every response carries {@code Cache-Control: private, no-store}, matching
 * {@code PublicOrderController} — these bodies are buyer-specific and must
 * never sit in a shared cache.
 */
@RestController
public class BuyerMeController {

    private static final String NO_STORE = "private, no-store";

    private final BuyerAccountRepository accounts;
    private final BuyerAccountEmailRepository emails;
    private final BuyerSessionService sessionService;

    public BuyerMeController(BuyerAccountRepository accounts,
                             BuyerAccountEmailRepository emails,
                             BuyerSessionService sessionService) {
        this.accounts = accounts;
        this.emails = emails;
        this.sessionService = sessionService;
    }

    @GetMapping("/api/v1/buyer/me")
    @Transactional(readOnly = true)
    public ResponseEntity<BuyerMeResponse> me(@CurrentBuyer BuyerPrincipal buyer) {
        BuyerAccount account = accounts.findById(buyer.accountId())
                .orElseThrow(() -> ApiException.notFound("Account"));

        var body = BuyerMeResponse.of(
                account, emails.findByBuyerAccountIdOrderByCreatedAtAsc(account.getId()));

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(body);
    }

    /**
     * Revokes every session on the account, including the acting one, and
     * clears the cookie. This is the control you need on the day someone's
     * inbox is compromised, so it deliberately does not spare the caller.
     */
    @PostMapping("/api/v1/buyer/sessions/revoke-all")
    public ResponseEntity<Void> revokeAll(@CurrentBuyer BuyerPrincipal buyer) {
        sessionService.revokeAll(buyer.accountId());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionService.clearCookie().toString())
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .build();
    }
}

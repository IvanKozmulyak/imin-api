package com.imin.iminapi.buyer.controller;

import com.imin.iminapi.buyer.dto.BuyerDeletionResponse;
import com.imin.iminapi.buyer.security.BuyerPrincipal;
import com.imin.iminapi.buyer.security.CurrentBuyer;
import com.imin.iminapi.buyer.service.BuyerAccountDeletionService;
import com.imin.iminapi.buyer.service.BuyerSessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Account deletion — {@code /api/v1/buyer/account/delete} and its cancel
 * (epic §7.1).
 *
 * <p>Authenticated by {@code SecurityConfig}'s
 * {@code .requestMatchers("/api/v1/buyer/**").hasRole("BUYER")}; neither path is
 * on the permit-list, so both need a live buyer session and an organizer bearer
 * token cannot reach either. That is the whole access-control story and it needs
 * no addition to {@code SecurityConfig}.
 *
 * <p><b>Cancel lives under {@code /account/delete/cancel}, not
 * {@code /account/cancel}.</b> That is the path {@code imin-public} already
 * ships ({@code lib/api/buyer.ts:575}), and the frontend for this slice is
 * already in production. It also reads better: the thing being cancelled is the
 * deletion, not the account.
 */
@RestController
public class BuyerAccountController {

    private static final String NO_STORE = "private, no-store";

    private final BuyerAccountDeletionService deletion;
    private final BuyerSessionService sessions;

    public BuyerAccountController(BuyerAccountDeletionService deletion,
                                  BuyerSessionService sessions) {
        this.deletion = deletion;
        this.sessions = sessions;
    }

    /**
     * Schedules erasure — and acts immediately (§7.1). By the time this returns,
     * every session on the account is revoked and every membership is
     * unsubscribed on every channel; only the destructive cascade waits out the
     * 30 days.
     *
     * <p>The cookie is cleared in the same response because the caller's own
     * session was among the ones revoked. Without it the browser keeps sending a
     * token the server has already killed, and the frontend meets an unexplained
     * 401 on its next call instead of a clean signed-out state.
     */
    @PostMapping("/api/v1/buyer/account/delete")
    public ResponseEntity<BuyerDeletionResponse> requestDeletion(@CurrentBuyer BuyerPrincipal buyer) {
        Instant deleteAt = deletion.requestDeletion(buyer.accountId());
        deletion.auditDeletionRequested(buyer.accountId(), deleteAt);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessions.clearCookie().toString())
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(new BuyerDeletionResponse(deleteAt));
    }

    /**
     * "Keep my account". Reaching this at all means the buyer signed in again,
     * because scheduling revoked every session — which is the point. Deletion is
     * never cancelled implicitly by signing in (§7.1): someone may sign in on day
     * 12 purely to pull up a ticket, and treating that as a change of heart would
     * be the product deciding on their behalf.
     */
    @PostMapping("/api/v1/buyer/account/delete/cancel")
    public ResponseEntity<Void> cancelDeletion(@CurrentBuyer BuyerPrincipal buyer) {
        deletion.auditDeletionCancelled(buyer.accountId());
        deletion.cancel(buyer.accountId());

        return ResponseEntity.noContent()
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .build();
    }
}

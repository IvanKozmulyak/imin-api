package com.imin.iminapi.controller.payout;

import com.imin.iminapi.controller.payout.dto.PayoutConnectResponse;
import com.imin.iminapi.controller.payout.dto.PayoutRowResponse;
import com.imin.iminapi.controller.payout.dto.PayoutsStatusResponse;
import com.imin.iminapi.controller.payout.dto.PayoutsSummaryResponse;
import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.payout.PayoutService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organizer Payouts tab API. Token-scoped like {@code /dashboard} and
 * {@code /org/audit}: the current org is resolved from the Bearer session via
 * {@code @CurrentUser AuthPrincipal} — there is NO {@code {orgId}} path
 * variable, and the client never supplies an org id. Auth is enforced globally
 * by {@code SecurityConfig}'s catch-all
 * {@code .requestMatchers("/api/v1/**").authenticated()} — these paths are
 * deliberately NOT on any permitAll list (a payouts leak would expose another
 * org's money).
 *
 * <p>Response shapes mirror the FE {@code PayoutRow} / {@code PayoutsStatus} /
 * {@code PayoutsSummary} / {@code Paginated<T>} types exactly so the
 * imin-webapp {@code api:check} drift gate stays green.
 */
@RestController
@RequestMapping("/api/v1/payouts")
public class PayoutController {

    private final PayoutService service;

    public PayoutController(PayoutService service) {
        this.service = service;
    }

    /** Summary tiles (inTransit / thisMonth / pending + optional labels). */
    @GetMapping("/summary")
    public PayoutsSummaryResponse summary(@CurrentUser AuthPrincipal p) {
        return service.summary(p.orgId());
    }

    /** Connect status header (stripeConnected / accountLast4 / dashboardUrl). */
    @GetMapping("/status")
    public PayoutsStatusResponse status(@CurrentUser AuthPrincipal p) {
        return service.status(p, p.orgId());
    }

    /** Paginated payout history, newest first. {@code page} is 1-based. */
    @GetMapping
    public PageResponse<PayoutRowResponse> list(@CurrentUser AuthPrincipal p,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int pageSize) {
        return service.list(p.orgId(), page, pageSize);
    }

    /**
     * Idempotent connect — ensures a connected account exists and returns a
     * fresh onboarding URL. Not consumed by the rendered PayoutsTab (its CTA
     * routes to /settings/payments), but registered in the FE idempotency
     * allowlist, so it honors the {@code Idempotency-Key} header like the other
     * idempotent organizer endpoints (refund, stripe/connect).
     */
    @PostMapping("/connect")
    public PayoutConnectResponse connect(
            @CurrentUser AuthPrincipal p,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return service.connect(p, p.orgId(), idempotencyKey);
    }
}

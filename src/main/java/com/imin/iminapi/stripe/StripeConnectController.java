package com.imin.iminapi.stripe;

import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Organizer-side Stripe Connect endpoints. All three require a Bearer token and that
 * the authenticated user belongs to {@code orgId}.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/stripe")
public class StripeConnectController {

    private final StripeConnectService connect;

    public StripeConnectController(StripeConnectService connect) {
        this.connect = connect;
    }

    /**
     * Idempotent — if an account already exists for the org, returns the same id and
     * {@code created=false}. Empty request body.
     */
    @PostMapping("/connect")
    public ConnectResponse connect(@CurrentUser AuthPrincipal p, @PathVariable UUID orgId) {
        var r = connect.getOrCreateAccount(p, orgId);
        return new ConnectResponse(r.accountId(), r.created());
    }

    @PostMapping("/onboarding-link")
    public OnboardingLinkResponse onboardingLink(@CurrentUser AuthPrincipal p,
                                                  @PathVariable UUID orgId,
                                                  @RequestBody(required = false) OnboardingLinkRequest body) {
        String returnUrl = body == null ? null : body.returnUrl();
        String refreshUrl = body == null ? null : body.refreshUrl();
        return new OnboardingLinkResponse(connect.createOnboardingLink(p, orgId, returnUrl, refreshUrl));
    }

    /**
     * Live status — never cached. Returns nulls/false if the org has no account yet.
     */
    @GetMapping("/status")
    public StatusResponse status(@CurrentUser AuthPrincipal p, @PathVariable UUID orgId) {
        var s = connect.getStatus(p, orgId);
        return new StatusResponse(s.accountId(), s.readyToReceivePayments(),
                s.onboardingComplete(), s.requirementsStatus());
    }

    // Wire-shape DTOs — kept inline because they only exist on this surface.

    public record ConnectResponse(String accountId, boolean created) {}

    public record OnboardingLinkRequest(String returnUrl, String refreshUrl) {}

    public record OnboardingLinkResponse(String url) {}

    public record StatusResponse(String accountId,
                                  boolean readyToReceivePayments,
                                  boolean onboardingComplete,
                                  String requirementsStatus) {}
}

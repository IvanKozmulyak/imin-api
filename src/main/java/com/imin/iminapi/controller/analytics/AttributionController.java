package com.imin.iminapi.controller.analytics;

import com.imin.iminapi.dto.analytics.AttributionResponse;
import com.imin.iminapi.dto.analytics.UntaggedLinksResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.analytics.AttributionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authed organizer UTM attribution read-models. Both endpoints are scoped to
 * the caller's org via {@link AuthPrincipal#orgId()} inside the service. These
 * paths fall under the {@code /api/v1/**} "authenticated" rule in
 * {@code SecurityConfig} — no extra security wiring is required.
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AttributionController {

    private final AttributionService attribution;

    public AttributionController(AttributionService attribution) {
        this.attribution = attribution;
    }

    @GetMapping("/attribution")
    public AttributionResponse attribution(@CurrentUser AuthPrincipal p) {
        return attribution.attribution(p);
    }

    @GetMapping("/untagged")
    public UntaggedLinksResponse untagged(@CurrentUser AuthPrincipal p) {
        return attribution.untagged(p);
    }
}

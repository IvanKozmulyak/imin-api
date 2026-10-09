package com.imin.iminapi.refund;

import com.imin.iminapi.refund.dto.EventRefundPlanResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Plan for the EventDetailPage "Refund all" dialog: every order of the event, uncapped.
 * Read-only — the refunds themselves still go through {@code POST /orders/{id}/refund}.
 * 404 for an event of another org; any member of the owning org may read it.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/refund-plan")
public class EventRefundPlanController {

    private final EventRefundPlanService plans;

    public EventRefundPlanController(EventRefundPlanService plans) {
        this.plans = plans;
    }

    @GetMapping
    public EventRefundPlanResponse plan(@PathVariable UUID eventId, @CurrentUser AuthPrincipal principal) {
        return plans.planForEvent(eventId, principal);
    }
}

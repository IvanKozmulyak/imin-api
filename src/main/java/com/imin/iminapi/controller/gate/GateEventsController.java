package com.imin.iminapi.controller.gate;

import com.imin.iminapi.dto.gate.GateEventSummary;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * GET /api/v1/gate/events
 *
 * <p>Lists the events the authenticated gate device can scan into right now —
 * the org's events whose {@code startsAt} is in the future or within the last
 * 24 hours. Ordered by {@code startsAt} ascending so the upcoming event sits
 * at the top of the gate UI's picker.
 *
 * <p>Gate-token only. The endpoint sits on the {@code /api/v1/gate/**} sub-
 * tree which {@link com.imin.iminapi.security.BearerTokenAuthFilter} whitelists
 * for gate principals; a non-gate (user) principal that somehow reaches this
 * controller is rejected with {@code 401 AUTH_MISSING}, because gate-events is
 * a device-only surface (the organizer dashboard has its own richer events
 * endpoint at {@code GET /api/v1/events}). Returns {@code []} when the org has
 * no scannable events — never 404.
 */
@RestController
public class GateEventsController {

    /** How far back from "now" a currently-running event remains scannable. */
    private static final Duration RUNNING_WINDOW = Duration.ofHours(24);

    private final EventRepository events;

    public GateEventsController(EventRepository events) {
        this.events = events;
    }

    @GetMapping("/api/v1/gate/events")
    public List<GateEventSummary> list(@CurrentUser AuthPrincipal me) {
        if (me == null || !me.isGate() || me.orgId() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_MISSING,
                    "Authentication required");
        }
        Instant cutoff = Instant.now().minus(RUNNING_WINDOW);
        return events.findScannableForGate(me.orgId(), cutoff).stream()
                .map(GateEventSummary::from)
                .toList();
    }
}

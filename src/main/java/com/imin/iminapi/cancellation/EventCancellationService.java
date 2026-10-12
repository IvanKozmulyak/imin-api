package com.imin.iminapi.cancellation;

import com.imin.iminapi.cancellation.EventCancellationRepository.PreviewRow;
import com.imin.iminapi.cancellation.EventCancellationRepository.StatusRow;
import com.imin.iminapi.cancellation.dto.CancelEventRequest;
import com.imin.iminapi.cancellation.dto.EventCancellationPreviewResponse;
import com.imin.iminapi.cancellation.dto.EventCancellationResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketReservationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RoleGuard;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import com.imin.iminapi.service.event.TicketTierService;
import com.imin.iminapi.stripe.StripeProperties;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Cancels a published event: sales stop under the event and tier locks, and every order is enrolled for
 * a full refund that {@link EventCancellationSweeper} carries out. The cancel itself is the refund decision.
 */
@Service
public class EventCancellationService {

    /** refunds.md §4 gives 5 working days to trigger a refund; 3 calendar days leaves time to act. */
    static final Duration OVERDUE_AFTER = Duration.ofDays(3);

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final TicketTierService tierService;
    private final TicketReservationRepository reservations;
    private final EventCancellationRepository cancellations;
    private final AuditLogger auditLogger;
    private final StripeProperties stripeProps;
    private final Clock clock;

    public EventCancellationService(EventRepository events, TicketTierRepository tiers,
                                    TicketTierService tierService, TicketReservationRepository reservations,
                                    EventCancellationRepository cancellations, AuditLogger auditLogger,
                                    StripeProperties stripeProps, Clock clock) {
        this.events = events;
        this.tiers = tiers;
        this.tierService = tierService;
        this.reservations = reservations;
        this.cancellations = cancellations;
        this.auditLogger = auditLogger;
        this.stripeProps = stripeProps;
        this.clock = clock;
    }

    /** The organizer's cancel: owner or admin, confirmed. */
    @Transactional
    @CacheEvict(value = "dashboard", allEntries = true)
    public EventCancellationResponse cancel(AuthPrincipal p, UUID eventId, CancelEventRequest body) {
        RoleGuard.requireAtLeast(p, UserRole.ADMIN, "cancel an event");
        if (body == null || !body.confirm()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                    "Set confirm:true to cancel the event", Map.of("confirm", "required"));
        }
        return cancel(p.orgId(), eventId, p.userId(), body.expectedOrders());
    }

    /**
     * Cancels the org's event, or answers the existing cancellation when it already is (idempotent).
     * Callers other than the organizer endpoint (an admin removal) authorise before calling.
     *
     * @param expectedOrders the preview's order count; a different count now leaves the event LIVE (409)
     */
    @Transactional
    @CacheEvict(value = "dashboard", allEntries = true)
    public EventCancellationResponse cancel(UUID orgId, UUID eventId, UUID actorUserId, Integer expectedOrders) {
        Instant now = now();
        // Lock first, then read the committed status as a scalar (a cached entity would not see a racing cancel).
        events.lockActiveForWrite(eventId, orgId);
        Event event = events.findActive(eventId)
                .filter(e -> e.getOrgId().equals(orgId))
                .orElseThrow(() -> ApiException.notFound("Event"));
        EventStatus status = events.findActiveStatus(eventId).orElseThrow(() -> ApiException.notFound("Event"));

        if (status == EventStatus.CANCELLED && cancellations.exists(eventId)) return status(eventId, now);
        if (status != EventStatus.LIVE || (event.getEndsAt() != null && !event.getEndsAt().isAfter(now))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Only a published event that has not ended can be cancelled");
        }

        // Same tier locks as unpublish: reserve re-reads the event status under them, so no hold slips past.
        tierService.lockForWrite(eventId, tiers.findIdsByEventId(eventId));
        if (expectedOrders != null) {
            long orders = cancellations.countOrders(eventId, testMode());
            if (orders != expectedOrders) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.EVENT_CANCEL_PLAN_CHANGED,
                        "Orders changed since the preview; review the new figures and confirm again",
                        Map.of("orders", String.valueOf(orders)));
            }
        }
        if (events.markCancelled(eventId, orgId, now) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Only a published event that has not ended can be cancelled");
        }
        cancellations.insertCancellation(eventId, orgId, actorUserId, now);
        cancellations.enroll(eventId, now);
        auditLogger.record(new AuthPrincipal(actorUserId, orgId, UserRole.ADMIN, null), AuditActions.EVENT_CANCELLED,
                "event", eventId, "Cancelled event \"" + event.getName() + "\"");
        return status(eventId, now);
    }

    /** What a cancel would refund now; any member of the org. */
    @Transactional(readOnly = true)
    public EventCancellationPreviewResponse preview(AuthPrincipal p, UUID eventId) {
        requireOwnEvent(p, eventId);
        long paid = 0, already = 0, disputed = 0, free = 0;
        Map<String, long[]> byCurrency = new TreeMap<>();
        List<PreviewRow> rows = cancellations.previewRows(eventId, testMode());
        for (PreviewRow r : rows) {
            CancellationAmounts owed = CancellationAmounts.of(r.totalMinor(), r.feeMinor(),
                    r.activeRefundedMinor(), r.activeFeeRefundedMinor());
            if (r.totalMinor() == 0) {
                free++;
            } else if (r.disputes() > 0) {
                disputed++;
            } else if (owed.refundMinor() <= 0) {
                already++;
            } else {
                paid++;
                long[] sum = byCurrency.computeIfAbsent(r.currency(), c -> new long[3]);
                sum[0] += owed.refundMinor();
                sum[1] += owed.feeMinor();
                sum[2] += owed.ticketPriceMinor();
            }
        }
        List<EventCancellationPreviewResponse.CurrencyTotal> totals = new ArrayList<>();
        byCurrency.forEach((currency, s) ->
                totals.add(new EventCancellationPreviewResponse.CurrencyTotal(currency, s[0], s[1], s[2])));
        return new EventCancellationPreviewResponse(rows.size(), paid, already, disputed, free,
                reservations.countHeldByEventId(eventId), totals);
    }

    /** Progress of the event's cancellation; any member of the org. 404 when it was never cancelled. */
    @Transactional(readOnly = true)
    public EventCancellationResponse status(AuthPrincipal p, UUID eventId) {
        requireOwnEvent(p, eventId);
        return status(eventId, now());
    }

    private EventCancellationResponse status(UUID eventId, Instant now) {
        StatusRow s = cancellations.status(eventId, now.minus(OVERDUE_AFTER), testMode())
                .orElseThrow(() -> ApiException.notFound("Cancellation"));
        return new EventCancellationResponse(eventId, s.cancelledAt(),
                new EventCancellationResponse.Orders(s.total(), s.refunded(), s.processing(), s.waitingOnDispute(),
                        s.settledByBank(), s.free(), s.needsAttention()),
                s.overdue(), s.emailed(), s.total());
    }

    private void requireOwnEvent(AuthPrincipal p, UUID eventId) {
        // 404, not 403: another org must not learn the event exists.
        if (!events.existsActiveInOrg(eventId, p.orgId())) throw ApiException.notFound("Event");
    }

    private boolean testMode() {
        return !stripeProps.isLiveKey();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}

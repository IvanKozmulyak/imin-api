package com.imin.iminapi.moderation;

import com.imin.iminapi.dto.org.OrgSuspensionResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.ModerationHold;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.ModerationHoldRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.stripe.StripeProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Staff place and lift moderation holds. Only users listed in {@code imin.moderation.staff-user-ids}
 * reach any of this; everyone else, and everyone while the list is blank, gets the standard 404.
 *
 * <p>Lifting closes one row and writes nothing else, so every other claim on the org (other holds,
 * open disputes, open refunds, a cancelled event) stays in force exactly as it was.
 */
@Service
public class ModerationHoldService {

    public static final String WARN_PAYOUT_IN_FLIGHT = "payout_in_flight";

    private final ModerationHoldRepository holds;
    private final OrganizationRepository orgs;
    private final EventRepository events;
    private final UserRepository users;
    private final ModerationProperties props;
    private final StripeProperties stripeProps;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public ModerationHoldService(ModerationHoldRepository holds, OrganizationRepository orgs,
                                 EventRepository events, UserRepository users, ModerationProperties props,
                                 StripeProperties stripeProps, ApplicationEventPublisher publisher, Clock clock) {
        this.holds = holds;
        this.orgs = orgs;
        this.events = events;
        this.users = users;
        this.props = props;
        this.stripeProps = stripeProps;
        this.publisher = publisher;
        this.clock = clock;
    }

    public record PlaceRequest(UUID orgId, UUID eventId, Boolean suspendSales, Boolean holdPayouts,
                               String ground, String facts, Boolean reasonWithheld) {}

    public record HoldView(UUID id, UUID orgId, UUID eventId, String scope, boolean suspendSales,
                           boolean holdPayouts, String ground, String facts, boolean reasonWithheld,
                           UUID placedBy, String placedByEmail, Instant placedAt, Instant noticeSentAt,
                           Instant liftedAt, UUID liftedBy, String liftedByEmail, String liftNote,
                           Instant liftNoticeSentAt, boolean active) {
        static HoldView of(ModerationHold h) {
            return new HoldView(h.getId(), h.getOrgId(), h.getEventId(), h.getEventId() == null ? "org" : "event",
                    h.isSuspendSales(), h.isHoldPayouts(), h.getGround(), h.getFacts(), h.isReasonWithheld(),
                    h.getPlacedBy(), h.getPlacedByEmail(), h.getPlacedAt(), h.getNoticeSentAt(),
                    h.getLiftedAt(), h.getLiftedBy(), h.getLiftedByEmail(), h.getLiftNote(),
                    h.getLiftNoticeSentAt(), h.isActive());
        }
    }

    public record Placed(HoldView hold, List<String> warnings) {}

    /** The buyer-facing refusal shared by quote, hosted checkout and the native payment intent. */
    public static ApiException salesSuspendedError() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.SALES_SUSPENDED, "Ticket sales for this event are paused");
    }

    /** 404 unless the caller is a human user on the staff list; a gate device never qualifies. */
    public void requireStaff(AuthPrincipal p) {
        if (p == null || p.kind() != AuthPrincipal.Kind.USER || !props.isStaff(p.userId())) {
            throw ApiException.notFound("Resource");
        }
    }

    @Transactional
    public Placed place(AuthPrincipal p, PlaceRequest req) {
        requireStaff(p);
        if (req == null) throw invalid("body", "required");
        Map<String, String> fields = new LinkedHashMap<>();
        if (req.orgId() == null) fields.put("orgId", "required");
        boolean sales = Boolean.TRUE.equals(req.suspendSales());
        boolean payouts = Boolean.TRUE.equals(req.holdPayouts());
        if (!sales && !payouts) fields.put("measures", "suspendSales or holdPayouts must be true");
        if (req.ground() == null || !ModerationHold.GROUNDS.contains(req.ground())) fields.put("ground", "unknown");
        String facts = req.facts() == null ? "" : req.facts().strip();
        if (facts.isEmpty() || facts.length() > 2000) fields.put("facts", "1 to 2000 characters");
        if (!fields.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Invalid hold", fields);
        }

        Organization org = orgs.findById(req.orgId()).orElseThrow(() -> ApiException.notFound("Organization"));
        if (req.eventId() != null) {
            Event event = events.findById(req.eventId()).orElse(null);
            if (event == null || !org.getId().equals(event.getOrgId())) throw ApiException.notFound("Event");
        }
        // Stripe pays a non-manual account out on its own schedule, so a hold would promise what it cannot do.
        if (payouts && (!stripeProps.isPayoutScheduleManual() || !org.isStripePayoutScheduleManual())) {
            throw ApiException.invalidState("This organizer is not on manual payouts, so imin cannot hold them. "
                    + "Move the account to manual payouts first (docs/ops/moderation-holds.md).");
        }

        ModerationHold h = new ModerationHold();
        h.setId(UUID.randomUUID());
        h.setOrgId(org.getId());
        h.setEventId(req.eventId());
        h.setSuspendSales(sales);
        h.setHoldPayouts(payouts);
        h.setGround(req.ground());
        h.setFacts(facts);
        h.setReasonWithheld(Boolean.TRUE.equals(req.reasonWithheld()));
        h.setPlacedBy(p.userId());
        h.setPlacedByEmail(emailOf(p.userId()));
        h.setPlacedAt(now());
        h = holds.save(h);
        // The statement of reasons goes out after commit; nothing is sent for a hold that rolled back.
        publisher.publishEvent(new ModerationHoldNotifier.ModerationHoldPlaced(h.getId()));
        return new Placed(HoldView.of(h), warnings(org, h.getEventId(), payouts));
    }

    @Transactional
    public HoldView lift(AuthPrincipal p, UUID holdId, String note) {
        requireStaff(p);
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (cleanNote != null && cleanNote.length() > 1000) throw invalid("note", "at most 1000 characters");
        if (!holds.existsById(holdId)) throw ApiException.notFound("Hold");
        if (holds.lift(holdId, p.userId(), emailOf(p.userId()), cleanNote, now()) == 0) {
            throw ApiException.invalidState("Hold already lifted");
        }
        publisher.publishEvent(new ModerationHoldNotifier.ModerationHoldLifted(holdId));
        return HoldView.of(holds.findById(holdId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<HoldView> listForOrg(AuthPrincipal p, UUID orgId) {
        requireStaff(p);
        if (orgId == null) throw invalid("orgId", "required");
        return holds.findByOrgIdOrderByPlacedAtDesc(orgId).stream().map(HoldView::of).toList();
    }

    /**
     * The caller's own org's active holds, newest first, as the dashboard banner shows them: ground and facts
     * are left out when withheld, and the event name is null when blank or the event is gone.
     */
    @Transactional(readOnly = true)
    public OrgSuspensionResponse activeForOrganizer(AuthPrincipal p) {
        if (p == null || p.kind() != AuthPrincipal.Kind.USER || p.orgId() == null) throw ApiException.notFound("Resource");
        List<OrgSuspensionResponse.Hold> out = new ArrayList<>();
        for (ModerationHold h : holds.findActiveByOrgId(p.orgId())) {
            String eventName = h.getEventId() == null ? null : events.findById(h.getEventId())
                    .filter(e -> e.getDeletedAt() == null && e.getName() != null && !e.getName().isBlank())
                    .map(Event::getName).orElse(null);
            boolean withheld = h.isReasonWithheld();
            out.add(new OrgSuspensionResponse.Hold(h.getId(), h.getEventId() == null ? "org" : "event",
                    h.getEventId(), eventName, h.isSuspendSales(), h.isHoldPayouts(),
                    withheld ? null : h.getGround(), withheld ? null : h.getFacts(), withheld,
                    h.getPlacedAt(), props.getAppealEmail()));
        }
        return new OrgSuspensionResponse(out);
    }

    /** A payout already planned, submitted or retrying in the hold's scope: the hold cannot stop or recall it. */
    List<String> warnings(Organization org, UUID eventId, boolean holdPayouts) {
        List<String> out = new ArrayList<>();
        if (!holdPayouts) return out;
        long inFlight = eventId == null
                ? holds.countPayoutsInFlightForOrg(org.getId())
                : holds.countPayoutsInFlightForEvent(org.getId(), eventId);
        if (inFlight > 0L) out.add(WARN_PAYOUT_IN_FLIGHT);
        return out;
    }

    private String emailOf(UUID userId) {
        return users.findById(userId).map(User::getEmail).orElse(null);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Invalid hold", Map.of(field, message));
    }
}

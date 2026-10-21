package com.imin.iminapi.referral;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.referral.dto.PerkGivenResponse;
import com.imin.iminapi.referral.dto.PutReferralPerkRequest;
import com.imin.iminapi.referral.dto.ReferralPerkDto;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import com.imin.iminapi.util.OrderNumber;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * The event's crew-link perk and its unlocks. Setting, stopping or marking a perk given answers 404 while
 * {@code imin.referral.perks-enabled} is false, and nothing unlocks then. Saving a perk re-evaluates the whole event,
 * so lowering the threshold unlocks buyers already past it; an existing unlock keeps the label it was promised.
 */
@Service
public class ReferralPerkService {

    public static final String VIA_GATE = "gate";
    public static final String VIA_DASHBOARD = "dashboard";

    private final EventReferralPerkRepository perks;
    private final ReferralPerkUnlockRepository unlocks;
    private final EventRepository events;
    private final ReferralProperties props;
    private final AuditLogger audit;
    private final Clock clock;

    public ReferralPerkService(EventReferralPerkRepository perks, ReferralPerkUnlockRepository unlocks,
                               EventRepository events, ReferralProperties props, AuditLogger audit, Clock clock) {
        this.perks = perks;
        this.unlocks = unlocks;
        this.events = events;
        this.props = props;
        this.audit = audit;
        this.clock = clock;
    }

    public boolean perksEnabled() {
        return props.isPerksEnabled();
    }

    /** The perk buyers may be promised now: the flag is on and the event's perk is enabled. */
    @Transactional(readOnly = true)
    public Optional<EventReferralPerk> activePerk(UUID eventId) {
        if (!props.isPerksEnabled()) return Optional.empty();
        return perks.findById(eventId).filter(EventReferralPerk::isEnabled);
    }

    /** The event's perk row whatever its state, for the organizer; empty while the flag is off. */
    @Transactional(readOnly = true)
    public Optional<EventReferralPerk> perkForOrganizer(UUID eventId) {
        if (!props.isPerksEnabled()) return Optional.empty();
        return perks.findById(eventId);
    }

    @Transactional
    public ReferralPerkDto put(AuthPrincipal principal, UUID eventId, PutReferralPerkRequest body) {
        requirePerks();
        requireOwnEvent(eventId, principal);
        Instant now = now();
        perks.upsert(eventId, body.label(), body.threshold().shortValue(), now, principal.userId());
        unlocks.insertUnlocksForEvent(eventId, now);
        audit.record(principal, AuditActions.REFERRAL_PERK_SAVED, "event", eventId,
                "Crew perk saved: \"" + body.label() + "\" at " + body.threshold() + " ticket(s)");
        return ReferralPerkDto.from(perks.findById(eventId).orElseThrow());
    }

    /** Stop new unlocks. Existing unlocks and links stay; stopping a stopped perk is a no-op. */
    @Transactional
    public void stop(AuthPrincipal principal, UUID eventId) {
        requirePerks();
        requireOwnEvent(eventId, principal);
        if (perks.disable(eventId, now(), principal.userId()) == 1) {
            audit.record(principal, AuditActions.REFERRAL_PERK_STOPPED, "event", eventId,
                    "Crew perk stopped for new unlocks");
        } else if (!perks.existsById(eventId)) {
            throw ApiException.notFound("Perk");
        }
    }

    /** Unlock the referrer's perk if it has reached the threshold. Idempotent; a no-op while the flag is off. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void evaluate(UUID referrerOrderId) {
        if (!props.isPerksEnabled() || referrerOrderId == null) return;
        unlocks.insertUnlocksForReferrer(referrerOrderId, now());
    }

    /** {@link #evaluate} for every referrer of the event; heals unlocks a crashed listener missed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void evaluateEvent(UUID eventId) {
        if (!props.isPerksEnabled()) return;
        unlocks.insertUnlocksForEvent(eventId, now());
    }

    /** The unlock row of an order of this event, if any. */
    @Transactional(readOnly = true)
    public Optional<ReferralPerkUnlock> unlockOf(UUID eventId, UUID orderId) {
        return unlocks.findById(orderId).filter(u -> u.getEventId().equals(eventId));
    }

    /**
     * Marks the perk given once: a conditional update, so two door phones racing on one buyer give it once and the
     * second answers already_given. The caller has already checked that the event belongs to the actor's org.
     */
    @Transactional
    public PerkGivenResponse markGiven(UUID eventId, UUID orderId, String via, String byLabel) {
        int rows = unlocks.markGiven(eventId, orderId, via, cap(byLabel), now());
        Optional<ReferralPerkUnlock> unlock = unlockOf(eventId, orderId);
        if (unlock.isEmpty()) return new PerkGivenResponse("not_unlocked", null);
        ReferralPerkUnlock u = unlock.get();
        return new PerkGivenResponse(rows == 1 ? "given" : "already_given",
                new PerkGivenResponse.Perk(u.getLabel(), u.getGivenAt()));
    }

    @Transactional
    public PerkGivenResponse markGivenByOrganizer(AuthPrincipal principal, UUID eventId, UUID orderId) {
        requirePerks();
        requireOwnEvent(eventId, principal);
        PerkGivenResponse r = markGiven(eventId, orderId, VIA_DASHBOARD, principal.actorLabel());
        if ("given".equals(r.result())) {
            audit.record(principal, AuditActions.REFERRAL_PERK_GIVEN, "order", orderId,
                    "Crew perk marked given from the dashboard — order " + OrderNumber.display(orderId));
        }
        return r;
    }

    /**
     * Back to unlocked, only if the perk is still marked given at {@code givenAt}: a stale Undo never clears a later
     * mark from the door. Already unlocked is a no-op; marked given at another time is 409 STALE_WRITE.
     */
    @Transactional
    public void undoGivenByOrganizer(AuthPrincipal principal, UUID eventId, UUID orderId, Instant givenAt) {
        requirePerks();
        requireOwnEvent(eventId, principal);
        if (givenAt == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "givenAt is required");
        }
        if (unlocks.undoGiven(eventId, orderId, givenAt.truncatedTo(ChronoUnit.MICROS)) == 1) {
            audit.record(principal, AuditActions.REFERRAL_PERK_GIVEN_UNDONE, "order", orderId,
                    "Crew perk given mark undone — order " + OrderNumber.display(orderId));
            return;
        }
        if (unlockOf(eventId, orderId).map(ReferralPerkUnlock::getGivenAt).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.STALE_WRITE,
                    "The perk was marked given again since; reload before undoing");
        }
    }

    /** 404 unless the event exists, is not deleted and belongs to the caller's org. */
    public Event requireOwnEvent(UUID eventId, AuthPrincipal principal) {
        Event event = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (principal == null || !event.getOrgId().equals(principal.orgId())) throw ApiException.notFound("Event");
        return event;
    }

    private void requirePerks() {
        if (!props.isPerksEnabled()) throw ApiException.notFound("Perk");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String cap(String label) {
        if (label == null) return null;
        return label.length() > 120 ? label.substring(0, 120) : label;
    }
}

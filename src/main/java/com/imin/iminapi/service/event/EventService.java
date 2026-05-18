package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.event.*;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import com.imin.iminapi.stripe.StripeConnectService;
import com.imin.iminapi.web.IfMatchSupport;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

@Service
public class EventService {

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final PromoCodeRepository promos;
    private final PredictionRepository predictions;
    private final EventValidator validator;
    private final IfMatchSupport ifMatch;
    private final TicketTierService tierService;
    private final StripeConnectService stripeConnect;
    /** Audit logger is optional so older 8-arg test constructors still work. */
    private final AuditLogger auditLogger;

    /** Legacy 8-arg constructor used by existing unit tests that don't wire audit. */
    public EventService(EventRepository events, TicketTierRepository tiers,
                        PromoCodeRepository promos, PredictionRepository predictions,
                        EventValidator validator, IfMatchSupport ifMatch,
                        TicketTierService tierService,
                        StripeConnectService stripeConnect) {
        this(events, tiers, promos, predictions, validator, ifMatch, tierService, stripeConnect, null);
    }

    /** Primary constructor — Spring picks this one in the running app. */
    @org.springframework.beans.factory.annotation.Autowired
    public EventService(EventRepository events, TicketTierRepository tiers,
                        PromoCodeRepository promos, PredictionRepository predictions,
                        EventValidator validator, IfMatchSupport ifMatch,
                        TicketTierService tierService,
                        StripeConnectService stripeConnect,
                        AuditLogger auditLogger) {
        this.events = events;
        this.tiers = tiers;
        this.promos = promos;
        this.predictions = predictions;
        this.stripeConnect = stripeConnect;
        this.validator = validator;
        this.ifMatch = ifMatch;
        this.tierService = tierService;
        this.auditLogger = auditLogger;
    }

    private void audit(AuthPrincipal p, String action, String targetType, UUID targetId, String summary) {
        if (auditLogger != null) auditLogger.record(p, action, targetType, targetId, summary);
    }

    private static String eventLabel(Event e) {
        String n = e.getName();
        return (n == null || n.isBlank()) ? "Untitled" : n;
    }

    @Transactional
    public EventDto createDraft(AuthPrincipal p, EventPatchRequest body) {
        Event e = new Event();
        e.setOrgId(p.orgId());
        e.setCreatedBy(p.userId());
        e.setSlug(generateSlug());
        applyPatch(e, body);
        try {
            Event saved = events.save(e);
            audit(p, AuditActions.EVENT_CREATED, "event", saved.getId(),
                    "Created event \"" + eventLabel(saved) + "\"");
            return EventDto.summary(saved);
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.duplicate("slug", "Event slug already taken in this organization");
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<EventDto> list(AuthPrincipal p, EventStatus status, int page, int pageSize) {
        var pg = PageRequest.of(Math.max(0, page - 1), Math.min(100, Math.max(1, pageSize)));
        var result = events.findVisibleByOrg(p.orgId(), status, pg);
        return PageResponse.from(result, EventDto::summary);
    }

    @Transactional(readOnly = true)
    public EventDto detail(AuthPrincipal p, UUID id) {
        Event e = loadOwned(p, id);
        var tiersList = tiers.findByEventIdOrderBySortOrderAsc(id).stream().map(TicketTierDto::from).toList();
        var promosList = promos.findByEventId(id).stream().map(PromoCodeDto::from).toList();
        var prediction = predictions.findById(id).map(PredictionDto::from).orElse(null);
        return EventDto.detail(e, tiersList, promosList, prediction);
    }

    @Transactional
    public EventDto patch(AuthPrincipal p, UUID id, String ifMatchHeader, EventPatchRequest body) {
        Event e = loadOwned(p, id);
        boolean changed = applyPatch(e, body);
        e.setUpdatedAt(Instant.now()); // ensure ETag changes even when @PreUpdate doesn't fire
        try {
            events.save(e);
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.duplicate("slug", "Event slug already taken in this organization");
        }
        // Tier reconcile runs after events.save so the slug-duplicate translation above doesn't
        // wrap any tier-validation errors. Both still share the outer @Transactional boundary,
        // so any tier failure rolls back the event update too.
        if (body != null && body.tiers() != null) {
            tierService.reconcileEmbedded(e, body.tiers());
        }
        if (body != null && body.promoCodes() != null) {
            reconcilePromoCodes(e.getId(), body.promoCodes());
        }
        // Skip noisy "updated" audit rows when the patch was a no-op on event fields
        // (tier/promo reconciles get their own audit rows from TicketTierService / PromoCodeService).
        if (changed) {
            audit(p, AuditActions.EVENT_UPDATED, "event", e.getId(),
                    "Updated event \"" + eventLabel(e) + "\"");
        }
        return detail(p, id);
    }

    @Transactional
    public EventDto publish(AuthPrincipal p, UUID id) {
        Event e = loadOwned(p, id);
        if (e.getStatus() == EventStatus.LIVE) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE, "Already published");
        }
        validator.validateForPublish(e);
        requireStripeIfPaid(p, e);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        events.save(e);
        audit(p, AuditActions.EVENT_PUBLISHED, "event", e.getId(),
                "Published event \"" + eventLabel(e) + "\"");
        return detail(p, id);
    }

    /**
     * Take a LIVE event back to DRAFT, hiding the public page. Blocked once any ticket
     * has been sold: at that point the organizer owes buyers a refund flow (separate
     * cancel/refund endpoint, TBD) — silently unpublishing would let an operator pocket
     * the money and disappear the event page.
     */
    @Transactional
    public EventDto unpublish(AuthPrincipal p, UUID id) {
        Event e = loadOwned(p, id);
        if (e.getStatus() != EventStatus.LIVE) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE, "Event is not published");
        }
        boolean anySold = tiers.findByEventIdOrderBySortOrderAsc(e.getId()).stream()
                .anyMatch(t -> t.getSold() > 0);
        if (anySold) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Cannot unpublish: tickets have been sold. Cancel the event and refund buyers first.");
        }
        e.setStatus(EventStatus.DRAFT);
        e.setUpdatedAt(Instant.now());
        events.save(e);
        audit(p, AuditActions.EVENT_UNPUBLISHED, "event", e.getId(),
                "Unpublished event \"" + eventLabel(e) + "\"");
        return detail(p, id);
    }

    /**
     * If the event has any paid tier (priceMinor &gt; 0), the org must have a Stripe
     * connected account that's active (transfer capability = active). Otherwise the
     * Checkout flow would fail at purchase time. Free events bypass this check.
     *
     * Hits Stripe live via {@link StripeConnectService#getStatus} — no cached column
     * exists by design (see imin-api/CLAUDE.md Stripe Connect section).
     */
    private void requireStripeIfPaid(AuthPrincipal p, Event e) {
        boolean hasPaidTier = tiers.findByEventIdOrderBySortOrderAsc(e.getId()).stream()
                .anyMatch(t -> t.getPriceMinor() > 0);
        if (!hasPaidTier) return;

        var status = stripeConnect.getStatus(p, p.orgId());
        if (!status.readyToReceivePayments()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ErrorCode.STRIPE_NOT_READY,
                    "Connect and finish Stripe onboarding before publishing a paid event.");
        }
    }

    private Event loadOwned(AuthPrincipal p, UUID id) {
        Event e = events.findActive(id).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");
        return e;
    }

    /**
     * Applies the patch and returns {@code true} when at least one direct
     * event field was provided (so an "Updated event" audit row makes sense).
     * The tier and promo-code reconcile paths are intentionally excluded —
     * those have their own dedicated audit actions.
     */
    private boolean applyPatch(Event e, EventPatchRequest b) {
        if (b == null) return false;
        boolean changed = false;
        if (b.name() != null) { e.setName(b.name()); changed = true; }
        if (b.slug() != null) { e.setSlug(b.slug().toLowerCase(Locale.ROOT)); changed = true; }
        if (b.visibility() != null) { e.setVisibility(EventVisibility.fromWire(b.visibility())); changed = true; }
        if (b.genre() != null) { e.setGenre(b.genre()); changed = true; }
        if (b.type() != null) { e.setType(b.type()); changed = true; }
        if (b.startsAt() != null) { e.setStartsAt(b.startsAt()); changed = true; }
        if (b.endsAt() != null) { e.setEndsAt(b.endsAt()); changed = true; }
        if (b.timezone() != null) { e.setTimezone(b.timezone()); changed = true; }
        if (b.venue() != null) {
            VenueDto v = b.venue();
            e.setVenueName(v.name());
            if (v.street() != null) e.setVenueStreet(v.street());
            if (v.city() != null) e.setVenueCity(v.city());
            if (v.postalCode() != null) e.setVenuePostalCode(v.postalCode());
            e.setVenueCountry(v.country());
            changed = true;
        }
        if (b.description() != null) { e.setDescription(b.description()); changed = true; }
        if (b.posterUrl() != null) { e.setPosterUrl(b.posterUrl()); changed = true; }
        if (b.videoUrl() != null) { e.setVideoUrl(b.videoUrl()); changed = true; }
        if (b.currency() != null) { e.setCurrency(b.currency()); changed = true; }
        if (b.onSaleAt() != null) { e.setOnSaleAt(b.onSaleAt()); changed = true; }
        if (b.saleClosesAt() != null) { e.setSaleClosesAt(b.saleClosesAt()); changed = true; }
        return changed;
    }

    /**
     * Reconcile the event's promo code list against the patch. The natural key is
     * (event_id, code), so we upsert by code: existing codes have their fields
     * updated (preserving {@code usedCount}), new codes are inserted, and codes
     * absent from the patch are deleted.
     *
     * Validates *before* mutating: a bad row anywhere in the list aborts the whole
     * reconcile (and the surrounding @Transactional rolls back the event update too).
     */
    private void reconcilePromoCodes(UUID eventId, List<PromoCodeEmbeddedPatch> patches) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        Set<String> seenCodes = new HashSet<>();
        for (int i = 0; i < patches.size(); i++) {
            PromoCodeEmbeddedPatch p = patches.get(i);
            String prefix = "promoCodes[" + i + "].";
            String code = p.code() == null ? null : p.code().trim();
            if (code == null || code.isEmpty()) {
                fieldErrors.put(prefix + "code", "required");
            } else if (code.length() > 64) {
                fieldErrors.put(prefix + "code", "≤ 64 chars");
            } else if (!seenCodes.add(code.toUpperCase(Locale.ROOT))) {
                fieldErrors.put(prefix + "code", "duplicate code in list");
            }
            if (p.discountPct() == null) {
                fieldErrors.put(prefix + "discountPct", "required");
            } else if (p.discountPct() < 1 || p.discountPct() > 100) {
                fieldErrors.put(prefix + "discountPct", "must be 1..100");
            }
            if (p.maxUses() == null) {
                fieldErrors.put(prefix + "maxUses", "required");
            } else if (p.maxUses() < 1) {
                fieldErrors.put(prefix + "maxUses", "must be ≥ 1");
            }
        }
        if (!fieldErrors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Invalid promo code data", fieldErrors);
        }

        // Upsert by uppercase code. Existing rows keep their id and usedCount.
        List<PromoCode> existing = promos.findByEventId(eventId);
        Map<String, PromoCode> existingByCode = new LinkedHashMap<>();
        for (PromoCode pc : existing) {
            existingByCode.put(pc.getCode().toUpperCase(Locale.ROOT), pc);
        }
        Set<String> patchedCodes = new HashSet<>();
        for (PromoCodeEmbeddedPatch p : patches) {
            String upper = p.code().trim().toUpperCase(Locale.ROOT);
            patchedCodes.add(upper);
            PromoCode pc = existingByCode.get(upper);
            if (pc == null) {
                pc = new PromoCode();
                pc.setEventId(eventId);
                pc.setCode(upper);
                pc.setEnabled(true);
            }
            pc.setDiscountPct(p.discountPct());
            pc.setMaxUses(p.maxUses());
            promos.save(pc);
        }
        for (PromoCode pc : existing) {
            if (!patchedCodes.contains(pc.getCode().toUpperCase(Locale.ROOT))) {
                promos.delete(pc);
            }
        }
    }

    private static final Random SLUG_RND = new Random();
    private static String generateSlug() {
        // Drafts get a placeholder slug. The wizard will overwrite it on autosave.
        return "draft-" + Long.toHexString(System.currentTimeMillis()) + "-" + Long.toHexString(SLUG_RND.nextLong() & 0xffff);
    }
}

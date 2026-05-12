package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.TicketTierCreateRequest;
import com.imin.iminapi.dto.event.TicketTierDto;
import com.imin.iminapi.dto.event.TicketTierEmbeddedPatch;
import com.imin.iminapi.dto.event.TicketTierPatchRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.TicketTierKind;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.stripe.StripeProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TicketTierService {

    private final TicketTierRepository tiers;
    private final EventRepository events;
    private final TicketTierValidator validator;
    private final Clock clock;
    /**
     * Best-effort Stripe sync. Optional so existing tests that don't wire Stripe can still
     * instantiate the service.
     */
    private final StripeProductService stripeProductService;

    /** Legacy 4-arg constructor used by existing unit tests that don't need Stripe sync. */
    public TicketTierService(TicketTierRepository tiers,
                             EventRepository events,
                             TicketTierValidator validator,
                             Clock clock) {
        this(tiers, events, validator, clock, null);
    }

    /** Primary constructor — Spring uses this one in the running app. */
    @Autowired
    public TicketTierService(TicketTierRepository tiers,
                             EventRepository events,
                             TicketTierValidator validator,
                             Clock clock,
                             StripeProductService stripeProductService) {
        this.tiers = tiers;
        this.events = events;
        this.validator = validator;
        this.clock = clock;
        this.stripeProductService = stripeProductService;
    }

    @Transactional
    public TicketTierDto create(AuthPrincipal p, UUID eventId, TicketTierCreateRequest req) {
        Event event = loadOwnedEvent(p, eventId);
        Map<String, String> errors = validator.validateCreate(req, event);
        if (!errors.isEmpty()) throw badRequest(errors);

        TicketTier tier = new TicketTier();
        tier.setEventId(eventId);
        tier.setName(req.name());
        tier.setKind(TicketTierKind.fromWire(req.kind()));
        tier.setPriceMinor(req.priceMinor());
        tier.setQuantity(req.quantity());
        tier.setSaleStartsAt(req.saleStartsAt());
        tier.setSaleClosesAt(req.saleClosesAt());
        tier.setSortOrder(req.sortOrder() != null ? req.sortOrder() : nextSortOrder(eventId));
        tier.setEnabled(req.enabled() != null ? req.enabled() : true);
        // sold defaults to 0 in entity

        tier = tiers.save(tier);
        bumpEventUpdatedAt(event);
        // Best-effort Stripe product sync — logs and continues on failure.
        syncStripeProduct(tier, event);
        return TicketTierDto.from(tier);
    }

    @Transactional
    public TicketTierDto patch(AuthPrincipal p, UUID eventId, UUID tierId, TicketTierPatchRequest req) {
        Event event = loadOwnedEvent(p, eventId);
        TicketTier tier = loadOwnedTier(eventId, tierId);

        Map<String, String> errors = validator.validatePatch(req, tier, event);
        if (!errors.isEmpty()) throw badRequest(errors);

        applyPatch(tier, req);
        tier = tiers.save(tier);
        bumpEventUpdatedAt(event);
        // Best-effort sync — picks up name/price changes.
        syncStripeProduct(tier, event);
        return TicketTierDto.from(tier);
    }

    @Transactional
    public void delete(AuthPrincipal p, UUID eventId, UUID tierId) {
        Event event = loadOwnedEvent(p, eventId);
        TicketTier tier = loadOwnedTier(eventId, tierId);
        tiers.delete(tier);
        bumpEventUpdatedAt(event);
    }

    /**
     * Used by EventService.patch in the embed path. The event is already loaded + ownership-checked
     * by the caller — this method does NOT re-check ownership. Participates in the caller's transaction.
     */
    @Transactional
    public void reconcileEmbedded(Event event, List<TicketTierEmbeddedPatch> patches) {
        if (patches == null) return;
        for (TicketTierEmbeddedPatch patch : patches) {
            if (patch.id() == null) {
                // create
                Map<String, String> errors = validator.validateEmbeddedPatch(patch, null, event);
                if (!errors.isEmpty()) throw badRequest(errors);
                TicketTier tier = new TicketTier();
                tier.setEventId(event.getId());
                applyEmbeddedAsCreate(tier, patch);
                tier = tiers.save(tier);
                syncStripeProduct(tier, event);
            } else {
                // update — referencing an unrelated tier id is a client bug → 400, not 404
                TicketTier tier = tiers.findByIdAndEventId(patch.id(), event.getId())
                        .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                                "Tier id " + patch.id() + " does not belong to event " + event.getId(),
                                Map.of("tiers", "tier " + patch.id() + " not under event")));
                Map<String, String> errors = validator.validateEmbeddedPatch(patch, tier, event);
                if (!errors.isEmpty()) throw badRequest(errors);
                applyEmbeddedAsPatch(tier, patch);
                tier = tiers.save(tier);
                syncStripeProduct(tier, event);
            }
        }
    }

    /** Null-safe Stripe product sync. No-op when Stripe is not wired (e.g. some unit tests). */
    private void syncStripeProduct(TicketTier tier, Event event) {
        if (stripeProductService != null) stripeProductService.syncTier(tier, event);
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private Event loadOwnedEvent(AuthPrincipal p, UUID eventId) {
        Event e = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");
        return e;
    }

    /** 404 with "Event" — don't leak whether the tier exists under a different event. */
    private TicketTier loadOwnedTier(UUID eventId, UUID tierId) {
        return tiers.findByIdAndEventId(tierId, eventId)
                .orElseThrow(() -> ApiException.notFound("Event"));
    }

    private int nextSortOrder(UUID eventId) {
        return tiers.findByEventIdOrderBySortOrderAsc(eventId).stream()
                .mapToInt(TicketTier::getSortOrder).max().orElse(-1) + 1;
    }

    private void bumpEventUpdatedAt(Event event) {
        // Match EventService.patch — explicit set so the ETag advances even if @PreUpdate doesn't fire
        event.setUpdatedAt(clock.instant());
        events.save(event);
    }

    private void applyPatch(TicketTier tier, TicketTierPatchRequest r) {
        if (r.name() != null) tier.setName(r.name());
        if (r.kind() != null) tier.setKind(TicketTierKind.fromWire(r.kind()));
        if (r.priceMinor() != null) tier.setPriceMinor(r.priceMinor());
        if (r.quantity() != null) tier.setQuantity(r.quantity());
        if (Boolean.TRUE.equals(r.clearSaleStartsAt())) {
            tier.setSaleStartsAt(null);
        } else if (r.saleStartsAt() != null) {
            tier.setSaleStartsAt(r.saleStartsAt());
        }
        if (Boolean.TRUE.equals(r.clearSaleClosesAt())) {
            tier.setSaleClosesAt(null);
        } else if (r.saleClosesAt() != null) {
            tier.setSaleClosesAt(r.saleClosesAt());
        }
        if (r.sortOrder() != null) tier.setSortOrder(r.sortOrder());
        if (r.enabled() != null) tier.setEnabled(r.enabled());
    }

    private void applyEmbeddedAsCreate(TicketTier tier, TicketTierEmbeddedPatch p) {
        // Validation guarantees required fields are present.
        tier.setName(p.name());
        tier.setKind(TicketTierKind.fromWire(p.kind()));
        tier.setPriceMinor(p.priceMinor());
        tier.setQuantity(p.quantity());
        tier.setSaleStartsAt(p.saleStartsAt());
        tier.setSaleClosesAt(p.saleClosesAt());
        tier.setSortOrder(p.sortOrder() != null ? p.sortOrder() : nextSortOrder(tier.getEventId()));
        tier.setEnabled(p.enabled() != null ? p.enabled() : true);
    }

    private void applyEmbeddedAsPatch(TicketTier tier, TicketTierEmbeddedPatch p) {
        if (p.name() != null) tier.setName(p.name());
        if (p.kind() != null) tier.setKind(TicketTierKind.fromWire(p.kind()));
        if (p.priceMinor() != null) tier.setPriceMinor(p.priceMinor());
        if (p.quantity() != null) tier.setQuantity(p.quantity());
        if (Boolean.TRUE.equals(p.clearSaleStartsAt())) {
            tier.setSaleStartsAt(null);
        } else if (p.saleStartsAt() != null) {
            tier.setSaleStartsAt(p.saleStartsAt());
        }
        if (Boolean.TRUE.equals(p.clearSaleClosesAt())) {
            tier.setSaleClosesAt(null);
        } else if (p.saleClosesAt() != null) {
            tier.setSaleClosesAt(p.saleClosesAt());
        }
        if (p.sortOrder() != null) tier.setSortOrder(p.sortOrder());
        if (p.enabled() != null) tier.setEnabled(p.enabled());
    }

    private ApiException badRequest(Map<String, String> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                "Invalid tier data", errors);
    }
}

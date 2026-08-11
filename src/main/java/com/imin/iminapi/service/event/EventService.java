package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.event.*;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.predictor.service.EventOutcomeService;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import com.imin.iminapi.stripe.StripeConnectService;
import com.imin.iminapi.util.CountryTimeZones;
import com.imin.iminapi.util.EventNormalization;
import com.imin.iminapi.web.IfMatchSupport;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
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
    /**
     * Predictor outcome-record writer (spec §6.1). Optional so older test constructors
     * that don't wire it still work; when present, {@link #publish} snapshots the frozen
     * pre-event fields inside the publish transaction (every LIVE event gets a snapshot).
     */
    private final EventOutcomeService outcomeService;

    /**
     * Concept lookup for the V71 AI-provenance stamp. Optional (nullable in legacy test
     * constructors); when absent, a supplied {@code sourceConceptId} is rejected as not found.
     */
    private final ConceptRepository concepts;

    /**
     * Optional predictor reactivity publisher (task scope B). Nullable in legacy test
     * constructors; when present, {@link #patch} and {@link #publish} emit AFTER_COMMIT domain
     * events so the predictor re-scores / re-forecasts in step with organizer edits.
     */
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** Legacy 8-arg constructor used by existing unit tests that don't wire audit. */
    public EventService(EventRepository events, TicketTierRepository tiers,
                        PromoCodeRepository promos, PredictionRepository predictions,
                        EventValidator validator, IfMatchSupport ifMatch,
                        TicketTierService tierService,
                        StripeConnectService stripeConnect) {
        this(events, tiers, promos, predictions, validator, ifMatch, tierService, stripeConnect, null, null, null);
    }

    /** 9-arg constructor used by tests that wire audit but not the predictor outcome writer. */
    public EventService(EventRepository events, TicketTierRepository tiers,
                        PromoCodeRepository promos, PredictionRepository predictions,
                        EventValidator validator, IfMatchSupport ifMatch,
                        TicketTierService tierService,
                        StripeConnectService stripeConnect,
                        AuditLogger auditLogger) {
        this(events, tiers, promos, predictions, validator, ifMatch, tierService, stripeConnect, auditLogger, null, null);
    }

    /** 10-arg constructor (pre-V71) used by tests that don't wire the concept repo. */
    public EventService(EventRepository events, TicketTierRepository tiers,
                        PromoCodeRepository promos, PredictionRepository predictions,
                        EventValidator validator, IfMatchSupport ifMatch,
                        TicketTierService tierService,
                        StripeConnectService stripeConnect,
                        AuditLogger auditLogger,
                        EventOutcomeService outcomeService) {
        this(events, tiers, promos, predictions, validator, ifMatch, tierService, stripeConnect,
                auditLogger, outcomeService, null);
    }

    /** 11-arg constructor (pre-scope-B) used by tests that don't wire predictor reactivity. */
    public EventService(EventRepository events, TicketTierRepository tiers,
                        PromoCodeRepository promos, PredictionRepository predictions,
                        EventValidator validator, IfMatchSupport ifMatch,
                        TicketTierService tierService,
                        StripeConnectService stripeConnect,
                        AuditLogger auditLogger,
                        EventOutcomeService outcomeService,
                        ConceptRepository concepts) {
        this(events, tiers, promos, predictions, validator, ifMatch, tierService, stripeConnect,
                auditLogger, outcomeService, concepts, null);
    }

    /** Primary constructor — Spring picks this one in the running app. */
    @org.springframework.beans.factory.annotation.Autowired
    public EventService(EventRepository events, TicketTierRepository tiers,
                        PromoCodeRepository promos, PredictionRepository predictions,
                        EventValidator validator, IfMatchSupport ifMatch,
                        TicketTierService tierService,
                        StripeConnectService stripeConnect,
                        AuditLogger auditLogger,
                        EventOutcomeService outcomeService,
                        ConceptRepository concepts,
                        org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.events = events;
        this.tiers = tiers;
        this.promos = promos;
        this.predictions = predictions;
        this.stripeConnect = stripeConnect;
        this.validator = validator;
        this.ifMatch = ifMatch;
        this.tierService = tierService;
        this.auditLogger = auditLogger;
        this.outcomeService = outcomeService;
        this.concepts = concepts;
        this.eventPublisher = eventPublisher;
    }

    private void audit(AuthPrincipal p, String action, String targetType, UUID targetId, String summary) {
        if (auditLogger != null) auditLogger.record(p, action, targetType, targetId, summary);
    }

    private static String eventLabel(Event e) {
        String n = e.getName();
        return (n == null || n.isBlank()) ? "Untitled" : n;
    }

    @Transactional
    @CacheEvict(value = "dashboard", key = "#p.orgId().toString()")
    public EventDto createDraft(AuthPrincipal p, EventPatchRequest body) {
        Event e = new Event();
        e.setOrgId(p.orgId());
        e.setCreatedBy(p.userId());
        e.setSlug(generateSlug());
        applyPatch(e, body);
        stampConceptProvenance(p, e, body);
        try {
            Event saved = events.save(e);
            audit(p, AuditActions.EVENT_CREATED, "event", saved.getId(),
                    "Created event \"" + eventLabel(saved) + "\"");
            // A draft created WITH an address geocodes straight away (V80). Empty
            // address => nothing to resolve, no event published.
            if (!EMPTY_VENUE_ADDRESS_KEY.equals(venueAddressKey(saved))) {
                publishVenueAddressChanged(saved.getId());
            }
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
    @CacheEvict(value = "dashboard", key = "#p.orgId().toString()")
    public EventDto patch(AuthPrincipal p, UUID id, String ifMatchHeader, EventPatchRequest body) {
        Event e = loadOwned(p, id);
        String addressBefore = venueAddressKey(e);
        boolean changed = applyPatch(e, body);
        String addressAfter = venueAddressKey(e);
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
        // Predictor reactivity (scope B): a material edit (event fields, tiers or promos) re-scores
        // a scored draft / re-forecasts a live event. Published AFTER_COMMIT; the listener debounces
        // autosave bursts and never scores an event the organizer has not already scored.
        boolean touched = changed
                || (body != null && body.tiers() != null)
                || (body != null && body.promoCodes() != null);
        if (touched && eventPublisher != null) {
            eventPublisher.publishEvent(new com.imin.iminapi.predictor.service.PredictorReactivityEvents.EventMutated(e.getId()));
        }
        // Venue coordinates (V80): re-geocode only when an address STRING actually moved.
        // Publishing on every patch would burn the Nominatim rate budget on autosaves.
        if (!addressBefore.equals(addressAfter)) publishVenueAddressChanged(e.getId());
        return detail(p, id);
    }

    /**
     * Identity of the venue address for change detection. Case-folded and trimmed so
     * "Berlin " → "berlin" is not treated as a move; anything that survives that is a
     * genuinely different address worth one geocoder call.
     */
    /** The key of an Event with no address at all — the "nothing to geocode" sentinel. */
    private static final String EMPTY_VENUE_ADDRESS_KEY = venueAddressKey(new Event());

    private static String venueAddressKey(Event e) {
        return norm(e.getVenueStreet()) + "|" + norm(e.getVenueCity()) + "|"
                + norm(e.getVenuePostalCode()) + "|" + norm(e.getVenueCountry());
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private void publishVenueAddressChanged(UUID eventId) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new VenueAddressChangedEvent(eventId));
        }
    }

    @Transactional
    @CacheEvict(value = "dashboard", key = "#p.orgId().toString()")
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
        // Predictor data foundation (spec §6.1): snapshot the frozen pre-event fields the
        // moment the event goes LIVE, inside this transaction — so every published event
        // has an outcome record and the corpus never misses a comparable.
        if (outcomeService != null) {
            outcomeService.freezeOnPublish(e);
        }
        audit(p, AuditActions.EVENT_PUBLISHED, "event", e.getId(),
                "Published event \"" + eventLabel(e) + "\"");
        // Predictor reactivity (scope B): baseline re-forecast the moment a previously-scored draft
        // goes live (the listener no-ops when the draft was never scored).
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new com.imin.iminapi.predictor.service.PredictorReactivityEvents.EventPublished(e.getId()));
        }
        return detail(p, id);
    }

    /**
     * Take a LIVE event back to DRAFT, hiding the public page. Blocked once any ticket
     * has been sold: at that point the organizer owes buyers a refund flow (separate
     * cancel/refund endpoint, TBD) — silently unpublishing would let an operator pocket
     * the money and disappear the event page.
     */
    @Transactional
    @CacheEvict(value = "dashboard", key = "#p.orgId().toString()")
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
        // Genre is lower-cased (V82): it is an internal facet token that ?genre= matches
        // EXACTLY, so "Techno" and "techno" were two filters over the same nights. Both
        // frontends must title-case it back for display.
        if (b.genre() != null) { e.setGenre(EventNormalization.genre(b.genre())); changed = true; }
        if (b.type() != null) { e.setType(b.type()); changed = true; }
        if (b.startsAt() != null) { e.setStartsAt(b.startsAt()); changed = true; }
        if (b.endsAt() != null) { e.setEndsAt(b.endsAt()); changed = true; }
        // Venue is applied BEFORE the timezone so a same-request country can seed the derived zone.
        if (b.venue() != null) {
            VenueDto v = b.venue();
            e.setVenueName(v.name());
            if (v.street() != null) e.setVenueStreet(v.street());
            // City keeps its typed case (see EventNormalization) — only whitespace is cleaned.
            // The case-insensitive merge happens on the derived venue_city_key.
            if (v.city() != null) e.setVenueCity(EventNormalization.city(v.city()));
            if (v.postalCode() != null) e.setVenuePostalCode(v.postalCode());
            e.setVenueCountry(normalizedCountry(v.country()));
            changed = true;
        }
        changed |= applyTimezone(e, b);
        if (b.description() != null) { e.setDescription(b.description()); changed = true; }
        if (b.posterUrl() != null) {
            // Provenance (V71): a PATCH that CHANGES the poster URL has unknown origin (could be
            // an AI-studio poster or a pasted link) — reset the stamp to NULL rather than let a
            // stale true/false claim ride along. The manual-upload path re-stamps false itself.
            if (!b.posterUrl().equals(e.getPosterUrl())) e.setPosterAiGenerated(null);
            e.setPosterUrl(b.posterUrl());
            changed = true;
        }
        if (b.videoUrl() != null) { e.setVideoUrl(b.videoUrl()); changed = true; }
        if (b.currency() != null) { e.setCurrency(b.currency()); changed = true; }
        if (b.onSaleAt() != null) { e.setOnSaleAt(b.onSaleAt()); changed = true; }
        if (b.saleClosesAt() != null) { e.setSaleClosesAt(b.saleClosesAt()); changed = true; }
        return changed;
    }

    /**
     * Venue country on the way in (V82): upper-cased ISO-3166 alpha-2, or {@code NULL} —
     * <b>never the empty string</b>. A blank country and a missing one are the same fact, and
     * storing them as two different values ({@code ''} vs {@code NULL}) is what split one Metz
     * into three chips on the buyer's cities page.
     *
     * <p>Anything non-blank that is not two letters is rejected with a 400 rather than silently
     * dropped: {@code venue_country} is {@code varchar(2)}, so a longer value used to surface as
     * a 500 from the driver, and nulling it out instead would quietly lose organizer input.
     */
    private static String normalizedCountry(String raw) {
        String country = EventNormalization.country(raw);
        if (country != null && !EventNormalization.isCountryCode(country)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Invalid venue country",
                    Map.of("venue.country", "must be an ISO-3166 alpha-2 code"));
        }
        return country;
    }

    /**
     * Resolves and sets the event timezone (bug 86ca74h6c).
     *
     * <p>An <b>explicit</b> non-UTC IANA zone on the request always wins and is validated against
     * {@link ZoneId#getAvailableZoneIds()} (unknown ⇒ 400 INVALID_REQUEST). Otherwise — the field
     * is absent, blank, or the literal {@code "UTC"} — we treat it as "not chosen" and derive the
     * zone from the venue country via {@link CountryTimeZones}, but only while the event still
     * carries the default/blank zone (so we never overwrite a previously derived or organizer-set
     * value). On an FR/EU events platform a stored {@code "UTC"} is effectively the unset default,
     * never a deliberate choice; this also makes the fix robust to deploy ordering, since the old
     * webapp keeps sending {@code "UTC"} on every autosave.
     *
     * @return {@code true} when the request carried a timezone field (for audit "changed" semantics)
     */
    private boolean applyTimezone(Event e, EventPatchRequest b) {
        String requested = b.timezone() == null ? null : b.timezone().trim();
        boolean explicit = requested != null && !requested.isEmpty() && !"UTC".equalsIgnoreCase(requested);
        if (explicit) {
            if (!ZoneId.getAvailableZoneIds().contains(requested)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                        "Unknown timezone",
                        Map.of("timezone", "must be a valid IANA time-zone id"));
            }
            e.setTimezone(requested);
            return true;
        }
        if (isDefaultZone(e.getTimezone())) {
            CountryTimeZones.zoneFor(e.getVenueCountry()).ifPresent(e::setTimezone);
        }
        // A client that explicitly sent "UTC" still counts as a supplied field for audit purposes.
        return requested != null && !requested.isEmpty();
    }

    /** True when the stored zone is null/blank or the plain {@code "UTC"} default (i.e. "not chosen"). */
    private static boolean isDefaultZone(String tz) {
        return tz == null || tz.isBlank() || "UTC".equalsIgnoreCase(tz.trim());
    }

    /**
     * V71 AI-provenance stamp, CREATE only. A verified {@code sourceConceptId} (a Concept
     * belonging to the caller's org) stamps {@code concept_ai_generated = true}; an unknown
     * or foreign id is a 404 (no cross-org probe). NO-FABRICATION: absence of the field
     * leaves the stamp NULL — today's FE promote flow sends nothing, so absence does NOT
     * mean "manual" and is never collapsed to false (V71 migration header has the full rule).
     */
    private void stampConceptProvenance(AuthPrincipal p, Event e, EventPatchRequest b) {
        if (b == null || b.sourceConceptId() == null) return;
        if (concepts == null || concepts.findByIdAndOrgId(b.sourceConceptId(), p.orgId()).isEmpty()) {
            throw ApiException.notFound("Concept");
        }
        e.setConceptAiGenerated(true);
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

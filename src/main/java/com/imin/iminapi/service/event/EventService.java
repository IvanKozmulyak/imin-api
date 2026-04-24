package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.event.*;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.web.IfMatchSupport;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

@Service
public class EventService {

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final PromoCodeRepository promos;
    private final PredictionRepository predictions;
    private final EventValidator validator;
    private final IfMatchSupport ifMatch;

    public EventService(EventRepository events, TicketTierRepository tiers,
                        PromoCodeRepository promos, PredictionRepository predictions,
                        EventValidator validator, IfMatchSupport ifMatch) {
        this.events = events;
        this.tiers = tiers;
        this.promos = promos;
        this.predictions = predictions;
        this.validator = validator;
        this.ifMatch = ifMatch;
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
        ifMatch.requireMatch(ifMatchHeader, e.getUpdatedAt());
        if (e.getStatus() != EventStatus.DRAFT) {
            // Only drafts are autosavable. Live events use targeted endpoints (out of V1 scope).
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Cannot edit a non-draft event via PATCH /events/:id");
        }
        applyPatch(e, body);
        e.setUpdatedAt(Instant.now()); // ensure ETag changes even when @PreUpdate doesn't fire
        try {
            events.save(e);
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.duplicate("slug", "Event slug already taken in this organization");
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
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        events.save(e);
        return detail(p, id);
    }

    private Event loadOwned(AuthPrincipal p, UUID id) {
        Event e = events.findActive(id).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");
        return e;
    }

    private void applyPatch(Event e, EventPatchRequest b) {
        if (b == null) return;
        if (b.name() != null) e.setName(b.name());
        if (b.slug() != null) e.setSlug(b.slug().toLowerCase(Locale.ROOT));
        if (b.visibility() != null) e.setVisibility(EventVisibility.fromWire(b.visibility()));
        if (b.genre() != null) e.setGenre(b.genre());
        if (b.type() != null) e.setType(b.type());
        if (b.startsAt() != null) e.setStartsAt(b.startsAt());
        if (b.endsAt() != null) e.setEndsAt(b.endsAt());
        if (b.timezone() != null) e.setTimezone(b.timezone());
        if (b.venue() != null) {
            VenueDto v = b.venue();
            e.setVenueName(v.name());
            if (v.street() != null) e.setVenueStreet(v.street());
            if (v.city() != null) e.setVenueCity(v.city());
            if (v.postalCode() != null) e.setVenuePostalCode(v.postalCode());
            e.setVenueCountry(v.country());
        }
        if (b.description() != null) e.setDescription(b.description());
        if (b.posterUrl() != null) e.setPosterUrl(b.posterUrl());
        if (b.videoUrl() != null) e.setVideoUrl(b.videoUrl());
        if (b.coverUrl() != null) e.setCoverUrl(b.coverUrl());
        if (b.currency() != null) e.setCurrency(b.currency());
        if (b.squadsEnabled() != null) e.setSquadsEnabled(b.squadsEnabled());
        if (b.minSquadSize() != null) e.setMinSquadSize(b.minSquadSize());
        if (b.squadDiscountPct() != null) e.setSquadDiscountPct(b.squadDiscountPct());
        if (b.onSaleAt() != null) e.setOnSaleAt(b.onSaleAt());
        if (b.saleClosesAt() != null) e.setSaleClosesAt(b.saleClosesAt());
    }

    private static final Random SLUG_RND = new Random();
    private static String generateSlug() {
        // Drafts get a placeholder slug. The wizard will overwrite it on autosave.
        return "draft-" + Long.toHexString(System.currentTimeMillis()) + "-" + Long.toHexString(SLUG_RND.nextLong() & 0xffff);
    }
}

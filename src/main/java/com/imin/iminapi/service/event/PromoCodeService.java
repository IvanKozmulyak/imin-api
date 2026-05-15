package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.PromoCodeCreateRequest;
import com.imin.iminapi.dto.event.PromoCodeDto;
import com.imin.iminapi.dto.event.PromoCodePatchRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.PromoCode;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-id promo code CRUD. Unlike {@link EventService#patch} (which is whole-list
 * reconcile and only works for drafts), these endpoints work on any event status —
 * they preserve {@code usedCount} on update because they target a specific row.
 */
@Service
public class PromoCodeService {

    private final PromoCodeRepository promos;
    private final EventRepository events;
    private final Clock clock;

    public PromoCodeService(PromoCodeRepository promos, EventRepository events, Clock clock) {
        this.promos = promos;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public PromoCodeDto create(AuthPrincipal p, UUID eventId, PromoCodeCreateRequest req) {
        Event event = loadOwnedEvent(p, eventId);
        Map<String, String> errors = new LinkedHashMap<>();
        String code = req.code() == null ? null : req.code().trim();
        validateCode(code, eventId, null, errors);
        validateDiscountPct(req.discountPct(), errors);
        validateMaxUses(req.maxUses(), null, errors);
        if (!errors.isEmpty()) throw badRequest(errors);

        PromoCode pc = new PromoCode();
        pc.setEventId(eventId);
        pc.setCode(code.toUpperCase(Locale.ROOT));
        pc.setDiscountPct(req.discountPct());
        pc.setMaxUses(req.maxUses());
        pc.setEnabled(req.enabled() != null ? req.enabled() : true);
        pc = promos.save(pc);
        bumpEventUpdatedAt(event);
        return PromoCodeDto.from(pc);
    }

    @Transactional
    public PromoCodeDto patch(AuthPrincipal p, UUID eventId, UUID promoId, PromoCodePatchRequest req) {
        Event event = loadOwnedEvent(p, eventId);
        PromoCode pc = loadOwnedPromo(eventId, promoId);
        Map<String, String> errors = new LinkedHashMap<>();

        String code = req.code();
        if (code != null) {
            String trimmed = code.trim();
            validateCode(trimmed, eventId, pc.getId(), errors);
            if (errors.isEmpty()) pc.setCode(trimmed.toUpperCase(Locale.ROOT));
        }
        if (req.discountPct() != null) {
            validateDiscountPct(req.discountPct(), errors);
            if (errors.isEmpty()) pc.setDiscountPct(req.discountPct());
        }
        if (req.maxUses() != null) {
            validateMaxUses(req.maxUses(), pc.getUsedCount(), errors);
            if (errors.isEmpty()) pc.setMaxUses(req.maxUses());
        }
        if (req.enabled() != null) pc.setEnabled(req.enabled());

        if (!errors.isEmpty()) throw badRequest(errors);
        pc = promos.save(pc);
        bumpEventUpdatedAt(event);
        return PromoCodeDto.from(pc);
    }

    @Transactional
    public void delete(AuthPrincipal p, UUID eventId, UUID promoId) {
        Event event = loadOwnedEvent(p, eventId);
        PromoCode pc = loadOwnedPromo(eventId, promoId);
        promos.delete(pc);
        bumpEventUpdatedAt(event);
    }

    // ── helpers ────────────────────────────────────────────────────────

    private Event loadOwnedEvent(AuthPrincipal p, UUID eventId) {
        Event e = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");
        return e;
    }

    private PromoCode loadOwnedPromo(UUID eventId, UUID promoId) {
        return promos.findByIdAndEventId(promoId, eventId)
                .orElseThrow(() -> ApiException.notFound("Promo code"));
    }

    private void bumpEventUpdatedAt(Event event) {
        event.setUpdatedAt(clock.instant());
        events.save(event);
    }

    private void validateCode(String code, UUID eventId, UUID excludeId, Map<String, String> errors) {
        if (code == null || code.isEmpty()) {
            errors.put("code", "required");
            return;
        }
        if (code.length() > 64) {
            errors.put("code", "≤ 64 chars");
            return;
        }
        Optional<PromoCode> existing = promos.findByEventIdAndCodeIgnoreCase(eventId, code);
        if (existing.isPresent() && (excludeId == null || !existing.get().getId().equals(excludeId))) {
            errors.put("code", "already exists for this event");
        }
    }

    private void validateDiscountPct(Integer pct, Map<String, String> errors) {
        if (pct == null) {
            errors.put("discountPct", "required");
        } else if (pct < 1 || pct > 100) {
            errors.put("discountPct", "must be 1..100");
        }
    }

    private void validateMaxUses(Integer maxUses, Integer usedCount, Map<String, String> errors) {
        if (maxUses == null) {
            errors.put("maxUses", "required");
        } else if (maxUses < 1) {
            errors.put("maxUses", "must be ≥ 1");
        } else if (usedCount != null && maxUses < usedCount) {
            errors.put("maxUses", "must be ≥ " + usedCount + " (already used)");
        }
    }

    private ApiException badRequest(Map<String, String> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                "Invalid promo code data", errors);
    }
}

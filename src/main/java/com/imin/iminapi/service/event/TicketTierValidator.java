package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.TicketTierCreateRequest;
import com.imin.iminapi.dto.event.TicketTierEmbeddedPatch;
import com.imin.iminapi.dto.event.TicketTierPatchRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.TicketTierKind;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TicketTierValidator {

    private static final int MAX_NAME_LENGTH = 128;

    private final Clock clock;

    public TicketTierValidator(Clock clock) {
        this.clock = clock;
    }

    /**
     * Validates a create request. Returns a (possibly empty) map of field → error.
     */
    public Map<String, String> validateCreate(TicketTierCreateRequest req, Event event) {
        Map<String, String> errors = new LinkedHashMap<>();

        // name: required, non-blank, ≤128
        validateName(req.name(), true, errors);

        // kind: required, valid enum
        if (req.kind() == null) {
            errors.put("kind", "required");
        } else {
            validateKindWire(req.kind(), errors);
        }

        // priceMinor: required, ≥ 0
        if (req.priceMinor() == null) {
            errors.put("priceMinor", "required");
        } else if (req.priceMinor() < 0) {
            errors.put("priceMinor", "must be ≥ 0");
        }

        // quantity: required, > 0
        if (req.quantity() == null) {
            errors.put("quantity", "required");
        } else if (req.quantity() <= 0) {
            errors.put("quantity", "must be > 0");
        }

        // saleClosesAt: optional; if set, > now AND ≤ event.startsAt (if set) AND ≤ event.endsAt (if set)
        if (req.saleClosesAt() != null) {
            validateSaleClosesAt(req.saleClosesAt(), event, errors);
        }

        // Cross-field: if both saleStartsAt and saleClosesAt are set, require closes > starts
        if (req.saleStartsAt() != null && req.saleClosesAt() != null
                && !errors.containsKey("saleClosesAt")
                && !req.saleClosesAt().isAfter(req.saleStartsAt())) {
            errors.put("saleClosesAt", "must be after saleStartsAt");
        }

        // sortOrder: optional, ≥ 0
        validateSortOrder(req.sortOrder(), errors);

        return errors;
    }

    /**
     * Validates a patch request against an existing tier and its event.
     * Null fields are skipped (leave unchanged). Returns field → error map.
     */
    public Map<String, String> validatePatch(TicketTierPatchRequest req, TicketTier existing, Event event) {
        Map<String, String> errors = new LinkedHashMap<>();

        // contradiction check: clearSaleClosesAt + non-null saleClosesAt
        if (Boolean.TRUE.equals(req.clearSaleClosesAt()) && req.saleClosesAt() != null) {
            errors.put("saleClosesAt", "contradictory: clearSaleClosesAt=true but saleClosesAt also provided");
        }

        // contradiction check: clearSaleStartsAt + non-null saleStartsAt
        if (Boolean.TRUE.equals(req.clearSaleStartsAt()) && req.saleStartsAt() != null) {
            errors.put("saleStartsAt", "contradictory: clearSaleStartsAt=true but saleStartsAt also provided");
        }

        // name: non-blank, ≤128 (only if provided)
        validateName(req.name(), false, errors);

        // kind: valid enum
        if (req.kind() != null) {
            validateKindWire(req.kind(), errors);
        }

        // priceMinor: ≥ 0
        if (req.priceMinor() != null && req.priceMinor() < 0) {
            errors.put("priceMinor", "must be ≥ 0");
        }

        // quantity: > 0
        if (req.quantity() != null && req.quantity() <= 0) {
            errors.put("quantity", "must be > 0");
        }

        // saleClosesAt: optional; if set (and not contradicted), > now AND ≤ event.startsAt AND ≤ event.endsAt
        if (req.saleClosesAt() != null && !errors.containsKey("saleClosesAt")) {
            validateSaleClosesAt(req.saleClosesAt(), event, errors);
        }

        // Cross-field: if both effective saleStartsAt and saleClosesAt are present after this patch,
        // require closes > starts. We compute "effective" by overlaying the patch onto the existing tier.
        Instant effectiveStarts = effectiveSaleStartsAt(req, existing);
        Instant effectiveCloses = effectiveSaleClosesAt(req, existing);
        if (effectiveStarts != null && effectiveCloses != null
                && !errors.containsKey("saleClosesAt")
                && !effectiveCloses.isAfter(effectiveStarts)) {
            errors.put("saleClosesAt", "must be after saleStartsAt");
        }

        // sortOrder: ≥ 0
        validateSortOrder(req.sortOrder(), errors);

        return errors;
    }

    private static Instant effectiveSaleStartsAt(TicketTierPatchRequest req, TicketTier existing) {
        if (Boolean.TRUE.equals(req.clearSaleStartsAt())) return null;
        if (req.saleStartsAt() != null) return req.saleStartsAt();
        return existing.getSaleStartsAt();
    }

    private static Instant effectiveSaleClosesAt(TicketTierPatchRequest req, TicketTier existing) {
        if (Boolean.TRUE.equals(req.clearSaleClosesAt())) return null;
        if (req.saleClosesAt() != null) return req.saleClosesAt();
        return existing.getSaleClosesAt();
    }

    /**
     * Validates an embedded patch entry. When existingOrNull is null, the entry is a create
     * (id == null), so required-field rules apply. Otherwise treated as a patch.
     */
    public Map<String, String> validateEmbeddedPatch(TicketTierEmbeddedPatch p, TicketTier existingOrNull, Event event) {
        if (p.id() == null && existingOrNull != null) {
            throw new IllegalArgumentException(
                    "validateEmbeddedPatch: existing tier provided but patch has no id");
        }
        if (p.id() != null && existingOrNull == null) {
            throw new IllegalArgumentException(
                    "validateEmbeddedPatch: patch has id but no existing tier provided");
        }

        if (existingOrNull == null) {
            // treat as create — map the embedded fields onto a create request
            TicketTierCreateRequest createReq = new TicketTierCreateRequest(
                    p.name(),
                    p.kind(),
                    p.priceMinor(),
                    p.quantity(),
                    p.saleStartsAt(),
                    p.saleClosesAt(),
                    p.sortOrder(),
                    p.enabled()
            );
            return validateCreate(createReq, event);
        } else {
            // treat as patch
            TicketTierPatchRequest patchReq = new TicketTierPatchRequest(
                    p.name(),
                    p.kind(),
                    p.priceMinor(),
                    p.quantity(),
                    p.saleStartsAt(),
                    p.clearSaleStartsAt(),
                    p.saleClosesAt(),
                    p.clearSaleClosesAt(),
                    p.sortOrder(),
                    p.enabled()
            );
            return validatePatch(patchReq, existingOrNull, event);
        }
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private void validateName(String name, boolean required, Map<String, String> errors) {
        if (name == null) {
            if (required) errors.put("name", "required");
            return;
        }
        if (name.isBlank()) {
            errors.put("name", "must be non-blank");
            return;
        }
        if (name.length() > MAX_NAME_LENGTH) {
            errors.put("name", "≤ " + MAX_NAME_LENGTH + " chars");
        }
    }

    private void validateSortOrder(Integer sortOrder, Map<String, String> errors) {
        if (sortOrder != null && sortOrder < 0) errors.put("sortOrder", "must be ≥ 0");
    }

    private void validateKindWire(String kindWire, Map<String, String> errors) {
        try {
            TicketTierKind.fromWire(kindWire);
        } catch (IllegalArgumentException e) {
            errors.put("kind", "invalid kind: " + kindWire);
        }
    }

    private void validateSaleClosesAt(Instant saleClosesAt, Event event, Map<String, String> errors) {
        Instant now = Instant.now(clock);
        if (!saleClosesAt.isAfter(now)) {
            errors.put("saleClosesAt", "must be in the future");
            // early-return: don't also report other bounds on the same field
            return;
        }
        if (event.getStartsAt() != null && saleClosesAt.isAfter(event.getStartsAt())) {
            errors.put("saleClosesAt", "must be ≤ event startsAt");
            return;
        }
        if (event.getEndsAt() != null && saleClosesAt.isAfter(event.getEndsAt())) {
            errors.put("saleClosesAt", "must be ≤ event endsAt");
        }
    }
}

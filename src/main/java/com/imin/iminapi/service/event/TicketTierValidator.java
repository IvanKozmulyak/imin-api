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
    private static final String LOCKED_MSG = "locked: tier has sold tickets";

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
        if (req.name() == null) {
            errors.put("name", "required");
        } else if (req.name().isBlank()) {
            errors.put("name", "must not be blank");
        } else if (req.name().length() > MAX_NAME_LENGTH) {
            errors.put("name", "≤128 chars");
        }

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

        // saleClosesAt: optional; if set, > now AND ≤ event.endsAt (if set)
        if (req.saleClosesAt() != null) {
            validateSaleClosesAt(req.saleClosesAt(), event, errors);
        }

        // sortOrder: optional, ≥ 0
        if (req.sortOrder() != null && req.sortOrder() < 0) {
            errors.put("sortOrder", "must be ≥ 0");
        }

        return errors;
    }

    /**
     * Validates a patch request against an existing tier and its event.
     * Null fields are skipped (leave unchanged). Returns field → error map.
     */
    public Map<String, String> validatePatch(TicketTierPatchRequest req, TicketTier existing, Event event) {
        Map<String, String> errors = new LinkedHashMap<>();
        boolean hasSold = existing.getSold() > 0;

        // contradiction check: clearSaleClosesAt + non-null saleClosesAt
        if (Boolean.TRUE.equals(req.clearSaleClosesAt()) && req.saleClosesAt() != null) {
            errors.put("saleClosesAt", "contradictory: clearSaleClosesAt=true but saleClosesAt also provided");
        }

        // name: non-blank, ≤128 (only if provided)
        if (req.name() != null) {
            if (req.name().isBlank()) {
                errors.put("name", "must not be blank");
            } else if (req.name().length() > MAX_NAME_LENGTH) {
                errors.put("name", "≤128 chars");
            }
        }

        // kind: valid enum, sales-locked
        if (req.kind() != null) {
            if (hasSold) {
                errors.put("kind", LOCKED_MSG);
            } else {
                validateKindWire(req.kind(), errors);
            }
        }

        // priceMinor: ≥ 0, sales-locked
        if (req.priceMinor() != null) {
            if (hasSold) {
                errors.put("priceMinor", LOCKED_MSG);
            } else if (req.priceMinor() < 0) {
                errors.put("priceMinor", "must be ≥ 0");
            }
        }

        // quantity: > 0; if sold > 0, must be ≥ sold
        if (req.quantity() != null) {
            if (req.quantity() <= 0) {
                errors.put("quantity", "must be > 0");
            } else if (hasSold && req.quantity() < existing.getSold()) {
                errors.put("quantity", "must be ≥ sold (" + existing.getSold() + ")");
            }
        }

        // saleClosesAt: optional; if set (and not contradicted), > now AND ≤ event.endsAt
        if (req.saleClosesAt() != null && !errors.containsKey("saleClosesAt")) {
            validateSaleClosesAt(req.saleClosesAt(), event, errors);
        }

        // sortOrder: ≥ 0
        if (req.sortOrder() != null && req.sortOrder() < 0) {
            errors.put("sortOrder", "must be ≥ 0");
        }

        return errors;
    }

    /**
     * Validates an embedded patch entry. When existingOrNull is null, the entry is a create
     * (id == null), so required-field rules apply. Otherwise treated as a patch.
     */
    public Map<String, String> validateEmbeddedPatch(TicketTierEmbeddedPatch p, TicketTier existingOrNull, Event event) {
        if (existingOrNull == null) {
            // treat as create — map the embedded fields onto a create request
            TicketTierCreateRequest createReq = new TicketTierCreateRequest(
                    p.name(),
                    p.kind(),
                    p.priceMinor(),
                    p.quantity(),
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
                    p.saleClosesAt(),
                    p.clearSaleClosesAt(),
                    p.sortOrder(),
                    p.enabled()
            );
            return validatePatch(patchReq, existingOrNull, event);
        }
    }

    // ── private helpers ────────────────────────────────────────────────────────

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
            return;
        }
        if (event.getEndsAt() != null && saleClosesAt.isAfter(event.getEndsAt())) {
            errors.put("saleClosesAt", "must be ≤ event endsAt");
        }
    }
}

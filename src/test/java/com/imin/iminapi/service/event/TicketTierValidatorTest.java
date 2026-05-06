package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.TicketTierCreateRequest;
import com.imin.iminapi.dto.event.TicketTierEmbeddedPatch;
import com.imin.iminapi.dto.event.TicketTierPatchRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.TicketTierKind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TicketTierValidatorTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Instant FUTURE = NOW.plusSeconds(3600);
    private static final Instant PAST = NOW.minusSeconds(3600);
    private static final Instant FAR_FUTURE = NOW.plusSeconds(86400 * 30);

    private final TicketTierValidator sut = new TicketTierValidator(
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    // ── helpers ─────────────────────────────────────────────────────────────────

    private Event eventWithEndsAt(Instant endsAt) {
        Event e = new Event();
        e.setEndsAt(endsAt);
        return e;
    }

    private Event eventNoEndsAt() {
        return eventWithEndsAt(null);
    }

    private TicketTierCreateRequest validCreate() {
        return new TicketTierCreateRequest("GA", "standard", 1000, 100, null, null, null);
    }

    private TicketTier existingTier(int sold) {
        TicketTier t = new TicketTier();
        t.setId(UUID.randomUUID());
        t.setName("GA");
        t.setKind(TicketTierKind.STANDARD);
        t.setPriceMinor(1000);
        t.setQuantity(100);
        t.setSold(sold);
        return t;
    }

    // ── validateCreate — name ────────────────────────────────────────────────────

    @Test
    void validateCreate_rejectsNullName() {
        TicketTierCreateRequest req = new TicketTierCreateRequest(null, "standard", 1000, 100, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("name");
    }

    @Test
    void validateCreate_rejectsBlankName() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("   ", "standard", 1000, 100, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("name");
    }

    @Test
    void validateCreate_rejectsNameTooLong() {
        String longName = "a".repeat(129);
        TicketTierCreateRequest req = new TicketTierCreateRequest(longName, "standard", 1000, 100, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("name");
    }

    // ── validateCreate — kind ────────────────────────────────────────────────────

    @Test
    void validateCreate_rejectsNullKind() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", null, 1000, 100, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("kind");
    }

    @Test
    void validateCreate_rejectsInvalidKindWire() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "EARLY_BIRD", 1000, 100, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("kind");
        assertThat(errors.get("kind")).contains("EARLY_BIRD");
    }

    @Test
    void validateCreate_acceptsValidKindWire_earlyBird() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "earlyBird", 1000, 100, null, null, null);
        assertThat(sut.validateCreate(req, eventNoEndsAt())).doesNotContainKey("kind");
    }

    @Test
    void validateCreate_acceptsValidKindWire_standard() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 1000, 100, null, null, null);
        assertThat(sut.validateCreate(req, eventNoEndsAt())).doesNotContainKey("kind");
    }

    @Test
    void validateCreate_acceptsValidKindWire_lateBird() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "lateBird", 1000, 100, null, null, null);
        assertThat(sut.validateCreate(req, eventNoEndsAt())).doesNotContainKey("kind");
    }

    @Test
    void validateCreate_acceptsValidKindWire_custom() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "custom", 1000, 100, null, null, null);
        assertThat(sut.validateCreate(req, eventNoEndsAt())).doesNotContainKey("kind");
    }

    // ── validateCreate — priceMinor ──────────────────────────────────────────────

    @Test
    void validateCreate_rejectsNullPriceMinor() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", null, 100, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("priceMinor");
    }

    @Test
    void validateCreate_rejectsNegativePriceMinor() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", -1, 100, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("priceMinor");
    }

    @Test
    void validateCreate_acceptsZeroPriceMinor() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 0, 100, null, null, null);
        assertThat(sut.validateCreate(req, eventNoEndsAt())).doesNotContainKey("priceMinor");
    }

    // ── validateCreate — quantity ────────────────────────────────────────────────

    @Test
    void validateCreate_rejectsNullQuantity() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 1000, null, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("quantity");
    }

    @Test
    void validateCreate_rejectsZeroQuantity() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 1000, 0, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("quantity");
    }

    @Test
    void validateCreate_rejectsNegativeQuantity() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 1000, -5, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("quantity");
    }

    // ── validateCreate — saleClosesAt ────────────────────────────────────────────

    @Test
    void validateCreate_acceptsNullSaleClosesAt() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 1000, 100, null, null, null);
        assertThat(sut.validateCreate(req, eventNoEndsAt())).doesNotContainKey("saleClosesAt");
    }

    @Test
    void validateCreate_rejectsPastSaleClosesAt() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 1000, 100, PAST, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("saleClosesAt");
    }

    @Test
    void validateCreate_rejectsSaleClosesAtAfterEventEndsAt() {
        Event event = eventWithEndsAt(FUTURE);
        Instant afterEndsAt = FUTURE.plusSeconds(1);
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 1000, 100, afterEndsAt, null, null);
        Map<String, String> errors = sut.validateCreate(req, event);
        assertThat(errors).containsKey("saleClosesAt");
    }

    @Test
    void validateCreate_acceptsSaleClosesAtBeforeEventEndsAt() {
        Event event = eventWithEndsAt(FAR_FUTURE);
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 1000, 100, FUTURE, null, null);
        assertThat(sut.validateCreate(req, event)).doesNotContainKey("saleClosesAt");
    }

    @Test
    void validateCreate_acceptsSaleClosesAtWhenEventEndsAtNull() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 1000, 100, FUTURE, null, null);
        assertThat(sut.validateCreate(req, eventNoEndsAt())).doesNotContainKey("saleClosesAt");
    }

    @Test
    void validateCreate_acceptsValidRequest_returnsEmptyMap() {
        Map<String, String> errors = sut.validateCreate(validCreate(), eventNoEndsAt());
        assertThat(errors).isEmpty();
    }

    // ── validatePatch — general ──────────────────────────────────────────────────

    @Test
    void validatePatch_nullFieldsLeftUnchanged_emptyMap() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).isEmpty();
    }

    @Test
    void validatePatch_blankNameRejected_butNullNameAccepted() {
        TicketTierPatchRequest blankName = new TicketTierPatchRequest("  ", null, null, null, null, null, null, null);
        assertThat(sut.validatePatch(blankName, existingTier(0), eventNoEndsAt())).containsKey("name");

        TicketTierPatchRequest nullName = new TicketTierPatchRequest(null, null, null, null, null, null, null, null);
        assertThat(sut.validatePatch(nullName, existingTier(0), eventNoEndsAt())).doesNotContainKey("name");
    }

    @Test
    void validatePatch_invalidKindRejected() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, "STANDARD", null, null, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).containsKey("kind");
    }

    @Test
    void validatePatch_negativePriceRejected() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, -1, null, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).containsKey("priceMinor");
    }

    @Test
    void validatePatch_zeroQuantityRejected() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, 0, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).containsKey("quantity");
    }

    @Test
    void validatePatch_clearSaleClosesAtAcceptedAlone() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, null, true, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).doesNotContainKey("saleClosesAt");
    }

    @Test
    void validatePatch_clearSaleClosesAtTrueWithNonNullSaleClosesAt_rejected() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, FUTURE, true, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).containsKey("saleClosesAt");
        assertThat(errors.get("saleClosesAt")).contains("contradictory");
    }

    @Test
    void validatePatch_negativeSortOrderRejected() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, null, null, -1, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).containsKey("sortOrder");
    }

    // ── validatePatch — sales-protection ────────────────────────────────────────

    @Test
    void validatePatch_priceMinorChange_blockedWhenSold() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, 500, null, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(1), eventNoEndsAt());
        assertThat(errors).containsKey("priceMinor");
        assertThat(errors.get("priceMinor")).contains("locked");
    }

    @Test
    void validatePatch_kindChange_blockedWhenSold() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, "earlyBird", null, null, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(1), eventNoEndsAt());
        assertThat(errors).containsKey("kind");
        assertThat(errors.get("kind")).contains("locked");
    }

    @Test
    void validatePatch_quantityBelowSold_blocked() {
        TicketTier tier = existingTier(10);
        tier.setQuantity(100);
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, 5, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, tier, eventNoEndsAt());
        assertThat(errors).containsKey("quantity");
    }

    @Test
    void validatePatch_quantityEqualSold_allowed() {
        TicketTier tier = existingTier(10);
        tier.setQuantity(100);
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, 10, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, tier, eventNoEndsAt());
        assertThat(errors).doesNotContainKey("quantity");
    }

    @Test
    void validatePatch_quantityAboveSold_allowed() {
        TicketTier tier = existingTier(10);
        tier.setQuantity(100);
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, 50, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, tier, eventNoEndsAt());
        assertThat(errors).doesNotContainKey("quantity");
    }

    @Test
    void validatePatch_nameEditAllowedWhenSold() {
        TicketTierPatchRequest req = new TicketTierPatchRequest("VIP", null, null, null, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(5), eventNoEndsAt());
        assertThat(errors).doesNotContainKey("name");
    }

    @Test
    void validatePatch_enabledToggleAllowedWhenSold() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, null, null, null, false);
        Map<String, String> errors = sut.validatePatch(req, existingTier(5), eventNoEndsAt());
        assertThat(errors).doesNotContainKey("enabled");
    }

    @Test
    void validatePatch_saleClosesAtChangeAllowedWhenSold() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, FUTURE, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(5), eventNoEndsAt());
        assertThat(errors).doesNotContainKey("saleClosesAt");
    }

    @Test
    void validatePatch_sortOrderChangeAllowedWhenSold() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, null, null, 2, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(5), eventNoEndsAt());
        assertThat(errors).doesNotContainKey("sortOrder");
    }

    // ── validateEmbeddedPatch ────────────────────────────────────────────────────

    @Test
    void validateEmbeddedPatch_idNullDelegatesToCreate() {
        // id == null → create semantics: required fields apply
        TicketTierEmbeddedPatch p = new TicketTierEmbeddedPatch(null, null, "standard", 1000, 100, null, null, null, null);
        Map<String, String> errors = sut.validateEmbeddedPatch(p, null, eventNoEndsAt());
        // name is required on create path
        assertThat(errors).containsKey("name");
    }

    @Test
    void validateEmbeddedPatch_idNonNullDelegatesToPatch() {
        // id != null → patch semantics: null name is ok (leave unchanged)
        TicketTierEmbeddedPatch p = new TicketTierEmbeddedPatch(
                UUID.randomUUID(), null, null, null, null, null, null, null, null);
        TicketTier existing = existingTier(0);
        Map<String, String> errors = sut.validateEmbeddedPatch(p, existing, eventNoEndsAt());
        assertThat(errors).isEmpty();
    }
}

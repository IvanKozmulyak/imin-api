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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void validateCreate_rejects_null_name() {
        TicketTierCreateRequest req = new TicketTierCreateRequest(null, "standard", 1000, 100, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("name");
    }

    @Test
    void validateCreate_rejects_blank_name() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("   ", "standard", 1000, 100, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("name");
    }

    @Test
    void validateCreate_rejects_name_too_long() {
        String longName = "a".repeat(129);
        TicketTierCreateRequest req = new TicketTierCreateRequest(longName, "standard", 1000, 100, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("name");
    }

    // ── validateCreate — kind ────────────────────────────────────────────────────

    @Test
    void validateCreate_rejects_null_kind() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", null, 1000, 100, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("kind");
    }

    @Test
    void validateCreate_rejects_invalid_kind_wire() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "EARLY_BIRD", 1000, 100, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("kind");
        assertThat(errors.get("kind")).contains("EARLY_BIRD");
    }

    @Test
    void validateCreate_accepts_valid_kind_wire_earlyBird() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "earlyBird", 1000, 100, null, null, null);
        assertThat(sut.validateCreate(req, eventNoEndsAt())).doesNotContainKey("kind");
    }

    @Test
    void validateCreate_accepts_valid_kind_wire_standard() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 1000, 100, null, null, null);
        assertThat(sut.validateCreate(req, eventNoEndsAt())).doesNotContainKey("kind");
    }

    @Test
    void validateCreate_accepts_valid_kind_wire_lateBird() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "lateBird", 1000, 100, null, null, null);
        assertThat(sut.validateCreate(req, eventNoEndsAt())).doesNotContainKey("kind");
    }

    @Test
    void validateCreate_accepts_valid_kind_wire_custom() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "custom", 1000, 100, null, null, null);
        assertThat(sut.validateCreate(req, eventNoEndsAt())).doesNotContainKey("kind");
    }

    // ── validateCreate — priceMinor ──────────────────────────────────────────────

    @Test
    void validateCreate_rejects_null_priceMinor() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", null, 100, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("priceMinor");
    }

    @Test
    void validateCreate_rejects_negative_priceMinor() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", -1, 100, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("priceMinor");
    }

    @Test
    void validateCreate_accepts_zero_priceMinor() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 0, 100, null, null, null);
        assertThat(sut.validateCreate(req, eventNoEndsAt())).doesNotContainKey("priceMinor");
    }

    // ── validateCreate — quantity ────────────────────────────────────────────────

    @Test
    void validateCreate_rejects_null_quantity() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 1000, null, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("quantity");
    }

    @Test
    void validateCreate_rejects_zero_quantity() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 1000, 0, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("quantity");
    }

    @Test
    void validateCreate_rejects_negative_quantity() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 1000, -5, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("quantity");
    }

    // ── validateCreate — saleClosesAt ────────────────────────────────────────────

    @Test
    void validateCreate_accepts_null_saleClosesAt() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 1000, 100, null, null, null);
        assertThat(sut.validateCreate(req, eventNoEndsAt())).doesNotContainKey("saleClosesAt");
    }

    @Test
    void validateCreate_rejects_past_saleClosesAt() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 1000, 100, PAST, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("saleClosesAt");
    }

    @Test
    void validateCreate_rejects_saleClosesAt_after_event_endsAt() {
        Event event = eventWithEndsAt(FUTURE);
        Instant afterEndsAt = FUTURE.plusSeconds(1);
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 1000, 100, afterEndsAt, null, null);
        Map<String, String> errors = sut.validateCreate(req, event);
        assertThat(errors).containsKey("saleClosesAt");
    }

    @Test
    void validateCreate_accepts_saleClosesAt_before_event_endsAt() {
        Event event = eventWithEndsAt(FAR_FUTURE);
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 1000, 100, FUTURE, null, null);
        assertThat(sut.validateCreate(req, event)).doesNotContainKey("saleClosesAt");
    }

    @Test
    void validateCreate_accepts_saleClosesAt_when_event_endsAt_null() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", "standard", 1000, 100, FUTURE, null, null);
        assertThat(sut.validateCreate(req, eventNoEndsAt())).doesNotContainKey("saleClosesAt");
    }

    @Test
    void validateCreate_accepts_valid_request_returns_empty_map() {
        Map<String, String> errors = sut.validateCreate(validCreate(), eventNoEndsAt());
        assertThat(errors).isEmpty();
    }

    // ── validatePatch — general ──────────────────────────────────────────────────

    @Test
    void validatePatch_null_fields_left_unchanged_empty_map() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).isEmpty();
    }

    @Test
    void validatePatch_blank_name_rejected() {
        TicketTierPatchRequest blankName = new TicketTierPatchRequest("  ", null, null, null, null, null, null, null);
        assertThat(sut.validatePatch(blankName, existingTier(0), eventNoEndsAt())).containsKey("name");
    }

    @Test
    void validatePatch_null_name_accepted() {
        TicketTierPatchRequest nullName = new TicketTierPatchRequest(null, null, null, null, null, null, null, null);
        assertThat(sut.validatePatch(nullName, existingTier(0), eventNoEndsAt())).doesNotContainKey("name");
    }

    @Test
    void validatePatch_invalid_kind_rejected() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, "STANDARD", null, null, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).containsKey("kind");
    }

    @Test
    void validatePatch_negative_price_rejected() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, -1, null, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).containsKey("priceMinor");
    }

    @Test
    void validatePatch_zero_quantity_rejected() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, 0, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).containsKey("quantity");
    }

    @Test
    void validatePatch_clearSaleClosesAt_accepted_alone() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, null, true, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).doesNotContainKey("saleClosesAt");
    }

    @Test
    void validatePatch_clearSaleClosesAt_true_with_non_null_saleClosesAt_rejected() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, FUTURE, true, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).containsKey("saleClosesAt");
        assertThat(errors.get("saleClosesAt")).contains("contradictory");
    }

    @Test
    void validatePatch_negative_sortOrder_rejected() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, null, null, -1, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).containsKey("sortOrder");
    }

    // ── validatePatch — sold > 0 no longer restricts edits ──────────────────────

    @Test
    void validatePatch_quantity_equal_sold_allowed() {
        TicketTier tier = existingTier(10);
        tier.setQuantity(100);
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, 10, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, tier, eventNoEndsAt());
        assertThat(errors).doesNotContainKey("quantity");
    }

    @Test
    void validatePatch_quantity_above_sold_allowed() {
        TicketTier tier = existingTier(10);
        tier.setQuantity(100);
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, 50, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, tier, eventNoEndsAt());
        assertThat(errors).doesNotContainKey("quantity");
    }

    @Test
    void validatePatch_name_edit_allowed_when_sold() {
        TicketTierPatchRequest req = new TicketTierPatchRequest("VIP", null, null, null, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(5), eventNoEndsAt());
        assertThat(errors).doesNotContainKey("name");
    }

    @Test
    void validatePatch_enabled_toggle_allowed_when_sold() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, null, null, null, false);
        Map<String, String> errors = sut.validatePatch(req, existingTier(5), eventNoEndsAt());
        assertThat(errors).doesNotContainKey("enabled");
    }

    @Test
    void validatePatch_saleClosesAt_change_allowed_when_sold() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, FUTURE, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(5), eventNoEndsAt());
        assertThat(errors).doesNotContainKey("saleClosesAt");
    }

    @Test
    void validatePatch_sortOrder_change_allowed_when_sold() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, null, null, 2, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(5), eventNoEndsAt());
        assertThat(errors).doesNotContainKey("sortOrder");
    }

    // ── validateEmbeddedPatch ────────────────────────────────────────────────────

    @Test
    void validateEmbeddedPatch_idNull_delegates_to_create() {
        // id == null → create semantics: required fields apply
        TicketTierEmbeddedPatch p = new TicketTierEmbeddedPatch(null, null, "standard", 1000, 100, null, null, null, null);
        Map<String, String> errors = sut.validateEmbeddedPatch(p, null, eventNoEndsAt());
        // name is required on create path
        assertThat(errors).containsKey("name");
    }

    @Test
    void validateEmbeddedPatch_idNonNull_delegates_to_patch() {
        // id != null → patch semantics: null name is ok (leave unchanged)
        TicketTierEmbeddedPatch p = new TicketTierEmbeddedPatch(
                UUID.randomUUID(), null, null, null, null, null, null, null, null);
        TicketTier existing = existingTier(0);
        Map<String, String> errors = sut.validateEmbeddedPatch(p, existing, eventNoEndsAt());
        assertThat(errors).isEmpty();
    }

    @Test
    void validateEmbeddedPatch_idNullWithNonNullExisting_throws() {
        TicketTierEmbeddedPatch p = new TicketTierEmbeddedPatch(
                null, "GA", "standard", 1000, 100, null, null, null, null);
        TicketTier existing = existingTier(0);
        assertThatThrownBy(() -> sut.validateEmbeddedPatch(p, existing, eventNoEndsAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no id");
    }

    @Test
    void validateEmbeddedPatch_idNonNullWithNullExisting_throws() {
        TicketTierEmbeddedPatch p = new TicketTierEmbeddedPatch(
                UUID.randomUUID(), null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> sut.validateEmbeddedPatch(p, null, eventNoEndsAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no existing tier");
    }
}

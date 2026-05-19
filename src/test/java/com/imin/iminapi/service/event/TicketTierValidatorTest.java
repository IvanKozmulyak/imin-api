package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.TicketTierCreateRequest;
import com.imin.iminapi.dto.event.TicketTierEmbeddedPatch;
import com.imin.iminapi.dto.event.TicketTierPatchRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
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
        return new TicketTierCreateRequest("GA", 1000, 100, null, null, null, null);
    }

    private TicketTier existingTier(int sold) {
        TicketTier t = new TicketTier();
        t.setId(UUID.randomUUID());
        t.setName("GA");
        t.setPriceMinor(1000);
        t.setQuantity(100);
        t.setSold(sold);
        return t;
    }

    // ── validateCreate — name ────────────────────────────────────────────────────

    @Test
    void validateCreate_rejects_null_name() {
        TicketTierCreateRequest req = new TicketTierCreateRequest(null, 1000, 100, null, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("name");
    }

    @Test
    void validateCreate_rejects_blank_name() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("   ", 1000, 100, null, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("name");
    }

    @Test
    void validateCreate_rejects_name_too_long() {
        String longName = "a".repeat(129);
        TicketTierCreateRequest req = new TicketTierCreateRequest(longName, 1000, 100, null, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("name");
    }

    // ── validateCreate — priceMinor ──────────────────────────────────────────────

    @Test
    void validateCreate_rejects_null_priceMinor() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", null, 100, null, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("priceMinor");
    }

    @Test
    void validateCreate_rejects_negative_priceMinor() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", -1, 100, null, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("priceMinor");
    }

    @Test
    void validateCreate_accepts_zero_priceMinor() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", 0, 100, null, null, null, null);
        assertThat(sut.validateCreate(req, eventNoEndsAt())).doesNotContainKey("priceMinor");
    }

    @Test
    void validateCreate_rejects_priceMinor_below_floor() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", 49, 100, null, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsEntry("priceMinor", "must be 0 (free) or at least 50");
    }

    @Test
    void validateCreate_accepts_priceMinor_at_floor() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", 50, 100, null, null, null, null);
        assertThat(sut.validateCreate(req, eventNoEndsAt())).doesNotContainKey("priceMinor");
    }

    @Test
    void validatePatch_rejects_priceMinor_below_floor() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(
                null, 25, null, null, null, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).containsEntry("priceMinor", "must be 0 (free) or at least 50");
    }

    @Test
    void validatePatch_accepts_priceMinor_zero() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(
                null, 0, null, null, null, null, null, null, null);
        assertThat(sut.validatePatch(req, existingTier(0), eventNoEndsAt()))
                .doesNotContainKey("priceMinor");
    }

    // ── validateCreate — quantity ────────────────────────────────────────────────

    @Test
    void validateCreate_rejects_null_quantity() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", 1000, null, null, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("quantity");
    }

    @Test
    void validateCreate_rejects_zero_quantity() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", 1000, 0, null, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("quantity");
    }

    @Test
    void validateCreate_rejects_negative_quantity() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", 1000, -5, null, null, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("quantity");
    }

    // ── validateCreate — saleClosesAt ────────────────────────────────────────────

    @Test
    void validateCreate_accepts_null_saleClosesAt() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", 1000, 100, null, null, null, null);
        assertThat(sut.validateCreate(req, eventNoEndsAt())).doesNotContainKey("saleClosesAt");
    }

    @Test
    void validateCreate_rejects_past_saleClosesAt() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", 1000, 100, null, PAST, null, null);
        Map<String, String> errors = sut.validateCreate(req, eventNoEndsAt());
        assertThat(errors).containsKey("saleClosesAt");
    }

    @Test
    void validateCreate_rejects_saleClosesAt_after_event_endsAt() {
        Event event = eventWithEndsAt(FUTURE);
        Instant afterEndsAt = FUTURE.plusSeconds(1);
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", 1000, 100, null, afterEndsAt, null, null);
        Map<String, String> errors = sut.validateCreate(req, event);
        assertThat(errors).containsKey("saleClosesAt");
    }

    @Test
    void validateCreate_accepts_saleClosesAt_before_event_endsAt() {
        Event event = eventWithEndsAt(FAR_FUTURE);
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", 1000, 100, null, FUTURE, null, null);
        assertThat(sut.validateCreate(req, event)).doesNotContainKey("saleClosesAt");
    }

    @Test
    void validateCreate_accepts_saleClosesAt_when_event_endsAt_null() {
        TicketTierCreateRequest req = new TicketTierCreateRequest("GA", 1000, 100, null, FUTURE, null, null);
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
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, null, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).isEmpty();
    }

    @Test
    void validatePatch_blank_name_rejected() {
        TicketTierPatchRequest blankName = new TicketTierPatchRequest("  ", null, null, null, null, null, null, null, null);
        assertThat(sut.validatePatch(blankName, existingTier(0), eventNoEndsAt())).containsKey("name");
    }

    @Test
    void validatePatch_null_name_accepted() {
        TicketTierPatchRequest nullName = new TicketTierPatchRequest(null, null, null, null, null, null, null, null, null);
        assertThat(sut.validatePatch(nullName, existingTier(0), eventNoEndsAt())).doesNotContainKey("name");
    }

    @Test
    void validatePatch_negative_price_rejected() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, -1, null, null, null, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).containsKey("priceMinor");
    }

    @Test
    void validatePatch_zero_quantity_rejected() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, 0, null, null, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).containsKey("quantity");
    }

    @Test
    void validatePatch_clearSaleClosesAt_accepted_alone() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, null, null, true, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).doesNotContainKey("saleClosesAt");
    }

    @Test
    void validatePatch_clearSaleClosesAt_true_with_non_null_saleClosesAt_rejected() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, null, FUTURE, true, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).containsKey("saleClosesAt");
        assertThat(errors.get("saleClosesAt")).contains("contradictory");
    }

    @Test
    void validatePatch_negative_sortOrder_rejected() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, null, null, null, -1, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(0), eventNoEndsAt());
        assertThat(errors).containsKey("sortOrder");
    }

    // ── validatePatch — sold > 0 no longer restricts edits ──────────────────────

    @Test
    void validatePatch_quantity_equal_sold_allowed() {
        TicketTier tier = existingTier(10);
        tier.setQuantity(100);
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, 10, null, null, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, tier, eventNoEndsAt());
        assertThat(errors).doesNotContainKey("quantity");
    }

    @Test
    void validatePatch_quantity_above_sold_allowed() {
        TicketTier tier = existingTier(10);
        tier.setQuantity(100);
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, 50, null, null, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, tier, eventNoEndsAt());
        assertThat(errors).doesNotContainKey("quantity");
    }

    @Test
    void validatePatch_quantity_below_sold_rejected() {
        TicketTier tier = existingTier(10);
        tier.setQuantity(100);
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, 5, null, null, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, tier, eventNoEndsAt());
        assertThat(errors).containsKey("quantity");
        assertThat(errors.get("quantity")).isEqualTo("must be ≥ sold (10)");
    }

    @Test
    void validateEmbeddedPatch_quantity_below_sold_rejected_on_update_branch() {
        TicketTier tier = existingTier(8);
        tier.setQuantity(100);
        TicketTierEmbeddedPatch p = new TicketTierEmbeddedPatch(
                tier.getId(), null, null, 3, null, null, null, null, null, null);
        Map<String, String> errors = sut.validateEmbeddedPatch(p, tier, eventNoEndsAt());
        assertThat(errors).containsKey("quantity");
        assertThat(errors.get("quantity")).isEqualTo("must be ≥ sold (8)");
    }

    @Test
    void validatePatch_name_edit_allowed_when_sold() {
        TicketTierPatchRequest req = new TicketTierPatchRequest("VIP", null, null, null, null, null, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(5), eventNoEndsAt());
        assertThat(errors).doesNotContainKey("name");
    }

    @Test
    void validatePatch_enabled_toggle_allowed_when_sold() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, null, null, null, null, false);
        Map<String, String> errors = sut.validatePatch(req, existingTier(5), eventNoEndsAt());
        assertThat(errors).doesNotContainKey("enabled");
    }

    @Test
    void validatePatch_saleClosesAt_change_allowed_when_sold() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, null, FUTURE, null, null, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(5), eventNoEndsAt());
        assertThat(errors).doesNotContainKey("saleClosesAt");
    }

    @Test
    void validatePatch_sortOrder_change_allowed_when_sold() {
        TicketTierPatchRequest req = new TicketTierPatchRequest(null, null, null, null, null, null, null, 2, null);
        Map<String, String> errors = sut.validatePatch(req, existingTier(5), eventNoEndsAt());
        assertThat(errors).doesNotContainKey("sortOrder");
    }

    // ── validateEmbeddedPatch ────────────────────────────────────────────────────

    @Test
    void validateEmbeddedPatch_idNull_delegates_to_create() {
        // id == null → create semantics: required fields apply
        TicketTierEmbeddedPatch p = new TicketTierEmbeddedPatch(null, null, 1000, 100, null, null, null, null, null, null);
        Map<String, String> errors = sut.validateEmbeddedPatch(p, null, eventNoEndsAt());
        // name is required on create path
        assertThat(errors).containsKey("name");
    }

    @Test
    void validateEmbeddedPatch_idNonNull_delegates_to_patch() {
        // id != null → patch semantics: null name is ok (leave unchanged)
        TicketTierEmbeddedPatch p = new TicketTierEmbeddedPatch(
                UUID.randomUUID(), null, null, null, null, null, null, null, null, null);
        TicketTier existing = existingTier(0);
        Map<String, String> errors = sut.validateEmbeddedPatch(p, existing, eventNoEndsAt());
        assertThat(errors).isEmpty();
    }

    @Test
    void validateEmbeddedPatch_idNullWithNonNullExisting_throws() {
        TicketTierEmbeddedPatch p = new TicketTierEmbeddedPatch(
                null, "GA", 1000, 100, null, null, null, null, null, null);
        TicketTier existing = existingTier(0);
        assertThatThrownBy(() -> sut.validateEmbeddedPatch(p, existing, eventNoEndsAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no id");
    }

    @Test
    void validateEmbeddedPatch_idNonNullWithNullExisting_throws() {
        TicketTierEmbeddedPatch p = new TicketTierEmbeddedPatch(
                UUID.randomUUID(), null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> sut.validateEmbeddedPatch(p, null, eventNoEndsAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no existing tier");
    }
}

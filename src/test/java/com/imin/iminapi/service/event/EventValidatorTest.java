package com.imin.iminapi.service.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventValidatorTest {

    private final EventValidator sut = new EventValidator();

    private Event valid() {
        Event e = new Event();
        e.setName("Test Night");
        e.setSlug("test-night");
        e.setStartsAt(Instant.parse("2026-06-01T20:00:00Z"));
        e.setEndsAt(Instant.parse("2026-06-02T04:00:00Z"));
        e.setVenueStreet("12 Main St");
        e.setVenueCity("Berlin");
        e.setVenuePostalCode("10115");
        e.setDescription("Stuff happens.");
        return e;
    }

    @Test
    void valid_event_passes() {
        sut.validateForPublish(valid());
    }

    @Test
    void missing_name_yields_FIELD_ERROR() {
        Event e = valid(); e.setName("");
        assertThatThrownBy(() -> sut.validateForPublish(e))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUBLISH_VALIDATION_FAILED)
                .extracting("fields").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("name");
    }

    @Test
    void multiple_missing_fields_collected_into_one_error() {
        Event e = valid();
        e.setName("");
        e.setStartsAt(null);
        e.setVenueCity("");
        try {
            sut.validateForPublish(e);
            assertThat(false).as("expected throw").isTrue();
        } catch (ApiException ex) {
            assertThat(ex.fields()).containsKeys("name", "startsAt", "venue.city");
        }
    }

    @Test
    void endsAt_before_startsAt_is_invalid() {
        Event e = valid();
        e.setEndsAt(e.getStartsAt().minusSeconds(60));
        assertThatThrownBy(() -> sut.validateForPublish(e))
                .extracting("fields").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("endsAt");
    }
}

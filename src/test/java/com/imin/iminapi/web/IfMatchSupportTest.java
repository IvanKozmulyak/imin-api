package com.imin.iminapi.web;

import com.imin.iminapi.security.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IfMatchSupportTest {

    private final IfMatchSupport sut = new IfMatchSupport();

    @Test
    void missing_header_is_a_no_op() {
        assertThatCode(() -> sut.requireMatch(null, Instant.now())).doesNotThrowAnyException();
        assertThatCode(() -> sut.requireMatch("", Instant.now())).doesNotThrowAnyException();
    }

    @Test
    void matching_header_passes() {
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        assertThatCode(() -> sut.requireMatch("\"" + updated.toString() + "\"", updated))
                .doesNotThrowAnyException();
        assertThatCode(() -> sut.requireMatch(updated.toString(), updated))
                .doesNotThrowAnyException();
    }

    @Test
    void mismatched_header_throws_stale_write() {
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        assertThatThrownBy(() -> sut.requireMatch("\"2026-01-01T00:00:00Z\"", updated))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("modified");
    }
}

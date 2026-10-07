package com.imin.iminapi.audience.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudienceMemberClassTest {

    @Test
    void parse_unknownOrNull_isEmpty() {
        assertThat(AudienceMemberClass.parse("vip")).isEmpty();
        assertThat(AudienceMemberClass.parse(null)).isEmpty();
        assertThat(AudienceMemberClass.parse("first_timer")).contains(AudienceMemberClass.FIRST_TIMER);
    }

    @Test
    void fromKey_unknown_throws() {
        assertThatThrownBy(() -> AudienceMemberClass.fromKey("FIRST_TIMER")).isInstanceOf(IllegalArgumentException.class);
    }
}

package com.imin.iminapi.marketing.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The campaign page shows a fix for the stored failure, never the stored text itself. */
class CampaignFailureReasonTest {

    static Stream<Arguments> storedValues() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of("ORG_LEGAL_IDENTITY_MISSING", "legal_identity_missing"),
                Arguments.of("segment_missing", "segment_missing"),
                Arguments.of("Segment not found", "segment_missing"),
                Arguments.of("nothing_sent", "nothing_sent"),
                Arguments.of("no recipients could be sent", "nothing_sent"),
                Arguments.of("provider_unavailable", "send_failed"),
                Arguments.of("could not execute statement [ERROR: duplicate key value violates unique constraint "
                        + "\"uq_campaign_recipient\" Detail: Key (email)=(fan@example.com) already exists.]", "send_failed"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("storedValues")
    void mapsStoredValuesToTheClosedSet(String stored, String wire) {
        assertThat(CampaignFailureReason.of(stored)).isEqualTo(wire);
    }
}

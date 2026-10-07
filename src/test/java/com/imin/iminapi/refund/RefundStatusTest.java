package com.imin.iminapi.refund;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RefundStatusTest {

    /** An unknown or new transient Stripe status stays PENDING: reading it as terminal would close a refund early. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(nullValues = "NULL", value = {
            "NULL, PENDING",
            "pending, PENDING",
            "succeeded, SUCCEEDED",
            "failed, FAILED",
            "canceled, CANCELED",
            "requires_action, PENDING",
            "something_new, PENDING"})
    void fromStripe_mapsKnownStatuses_andDefaultsTheRestToPending(String stripe, RefundStatus expected) {
        assertThat(RefundStatus.fromStripe(stripe)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(RefundStatus.class)
    void isTerminal_onlyForSucceededFailedCanceled(RefundStatus status) {
        assertThat(status.isTerminal())
                .isEqualTo(Set.of(RefundStatus.SUCCEEDED, RefundStatus.FAILED, RefundStatus.CANCELED).contains(status));
    }
}

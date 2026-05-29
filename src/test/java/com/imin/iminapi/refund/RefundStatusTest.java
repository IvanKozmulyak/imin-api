package com.imin.iminapi.refund;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefundStatusTest {

    @Test
    void maps_known_stripe_statuses() {
        assertThat(RefundStatus.fromStripe("pending")).isEqualTo(RefundStatus.PENDING);
        assertThat(RefundStatus.fromStripe("succeeded")).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(RefundStatus.fromStripe("failed")).isEqualTo(RefundStatus.FAILED);
        assertThat(RefundStatus.fromStripe("canceled")).isEqualTo(RefundStatus.CANCELED);
    }

    @Test
    void null_and_unknown_default_to_pending() {
        // We'd rather wait for an authoritative succeeded/failed than misclassify a new
        // transient Stripe status as terminal.
        assertThat(RefundStatus.fromStripe(null)).isEqualTo(RefundStatus.PENDING);
        assertThat(RefundStatus.fromStripe("requires_action")).isEqualTo(RefundStatus.PENDING);
        assertThat(RefundStatus.fromStripe("something_new")).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void terminal_flag_is_only_succeeded_failed_canceled() {
        assertThat(RefundStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(RefundStatus.FAILED.isTerminal()).isTrue();
        assertThat(RefundStatus.CANCELED.isTerminal()).isTrue();
        assertThat(RefundStatus.PENDING.isTerminal()).isFalse();
        assertThat(RefundStatus.REQUESTED.isTerminal()).isFalse();
    }
}

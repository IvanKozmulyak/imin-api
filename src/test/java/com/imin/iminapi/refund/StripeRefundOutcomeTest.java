package com.imin.iminapi.refund;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.PermissionException;
import com.stripe.exception.RateLimitException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.imin.iminapi.refund.StripeRefundOutcome.BALANCE_INSUFFICIENT;
import static com.imin.iminapi.refund.StripeRefundOutcome.DEFINITIVE;
import static com.imin.iminapi.refund.StripeRefundOutcome.UNCERTAIN;
import static org.assertj.core.api.Assertions.assertThat;

/** Only a refusal Stripe processed may release tickets; anything that may have created a refund keeps them. */
class StripeRefundOutcomeTest {

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("connection error", new ApiConnectionException("read timeout"), UNCERTAIN),
                Arguments.of("api 500", new ApiException("boom", "req_1", null, 500, null), UNCERTAIN),
                Arguments.of("api 409 lock", new ApiException("conflict", "req_1", null, 409, null), UNCERTAIN),
                Arguments.of("rate limit 429", new RateLimitException("slow", null, "req_1", "lock_timeout", 429, null),
                        UNCERTAIN),
                Arguments.of("idempotency 400", new IdempotencyException("key reused", "req_1", null, 400), UNCERTAIN),
                Arguments.of("non-Stripe", new IllegalStateException("npe in the SDK"), UNCERTAIN),
                Arguments.of("charge_already_refunded", new InvalidRequestException(
                        "already refunded", null, "req_1", "charge_already_refunded", 400, null), DEFINITIVE),
                Arguments.of("balance_insufficient", new InvalidRequestException(
                        "too low", "amount", "req_1", "balance_insufficient", 400, null), BALANCE_INSUFFICIENT),
                Arguments.of("card 402", new CardException("declined", "req_1", "card_declined", null, null, null, 402, null),
                        DEFINITIVE),
                Arguments.of("auth 401", new AuthenticationException("bad key", "req_1", null, 401), DEFINITIVE),
                Arguments.of("permission 403", new PermissionException("no access", "req_1", null, 403), DEFINITIVE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void classify(String name, Throwable thrown, StripeRefundOutcome expected) {
        assertThat(StripeRefundOutcome.classify(thrown)).isEqualTo(expected);
    }
}

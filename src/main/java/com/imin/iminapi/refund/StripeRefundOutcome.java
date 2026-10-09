package com.imin.iminapi.refund;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;

/**
 * What a failed refund create tells us about the money. Only {@link #DEFINITIVE} and
 * {@link #BALANCE_INSUFFICIENT} prove that Stripe processed the request and created no refund;
 * everything else may have created one, so its tickets stay claimed until the reconciler knows.
 */
public enum StripeRefundOutcome {
    /** Stripe processed the request and refused it; no refund exists. */
    DEFINITIVE,
    /** The connected account cannot fund the reversal; no refund exists, the platform may front it. */
    BALANCE_INSUFFICIENT,
    /** The request may or may not have created a refund (transport, 5xx, 409, 429, key replay, bug). */
    UNCERTAIN;

    public static StripeRefundOutcome classify(Throwable t) {
        if (!(t instanceof StripeException e)) return UNCERTAIN;
        // These say nothing about whether an earlier request with the same key created the refund.
        if (e instanceof ApiConnectionException || e instanceof IdempotencyException
                || e instanceof RateLimitException) {
            return UNCERTAIN;
        }
        int status = e.getStatusCode() == null ? 0 : e.getStatusCode();
        if (status == 0 || status >= 500 || status == 409 || status == 429) return UNCERTAIN;
        if (e instanceof InvalidRequestException) {
            return "balance_insufficient".equals(e.getCode()) ? BALANCE_INSUFFICIENT : DEFINITIVE;
        }
        if (e instanceof CardException || e instanceof AuthenticationException) return DEFINITIVE;
        return UNCERTAIN;
    }
}

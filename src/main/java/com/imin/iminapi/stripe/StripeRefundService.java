package com.imin.iminapi.stripe;

import com.imin.iminapi.refund.RefundReason;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.ApplicationFeeRefundCreateParams;
import com.stripe.param.RefundCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Wrapper around the Stripe SDK refund + application-fee-refund APIs.
 *
 * <p>Issues TWO Stripe API calls per logical refund:
 * <ol>
 *   <li>{@code refunds.create} with {@code reverse_transfer=true} so funds come
 *       back from the connected account, and {@code refund_application_fee=false}
 *       because we manage the fee refund explicitly in step 2.</li>
 *   <li>(when {@code appFeeRefundMinor > 0}) {@code applicationFees.refunds.create}
 *       with our computed proportional amount.</li>
 * </ol>
 *
 * <p>Two-call design over Stripe's all-or-nothing {@code refund_application_fee}
 * flag because we issue proportional fee refunds on every refund — even one-shot
 * "full" refunds — so a second partial refund later doesn't get the fee returned
 * twice.
 *
 * <p>Idempotency keys are deterministic: the caller passes {@code idempotencyKey}
 * (derived from our refund row id) and we use {@code idempotencyKey + "_fee"}
 * for the application-fee refund. Replays return the same Stripe objects.
 */
@Service
public class StripeRefundService {

    private static final Logger log = LoggerFactory.getLogger(StripeRefundService.class);

    private final StripeClient stripeClient;

    public StripeRefundService(StripeClient stripeClient) {
        this.stripeClient = stripeClient;
    }

    /**
     * @return the Stripe Refund object (with {@code id}, {@code charge}, {@code status} populated).
     * @throws StripeException unwrapped — caller maps to ApiException.
     */
    public Refund create(String paymentIntentId, long amountMinor, String currency,
                         RefundReason reason, long appFeeRefundMinor,
                         String idempotencyKey) throws StripeException {

        RefundCreateParams.Builder pb = RefundCreateParams.builder()
            .setPaymentIntent(paymentIntentId)
            .setAmount(amountMinor)
            .setReverseTransfer(true)
            .setRefundApplicationFee(false);
        String stripeReason = reason.toStripe();
        if (stripeReason != null) {
            // RefundCreateParams.Reason enum names match our RefundReason 1:1
            // (REQUESTED_BY_CUSTOMER, DUPLICATE, FRAUDULENT). OTHER maps to null
            // and is omitted.
            pb.setReason(RefundCreateParams.Reason.valueOf(reason.name()));
        }
        RefundCreateParams params = pb.build();

        RequestOptions opts = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        Refund refund = stripeClient.refunds().create(params, opts);
        log.info("[stripe-refund] created id={} status={} amount={} {} idemp={}",
                refund.getId(), refund.getStatus(), amountMinor, currency, idempotencyKey);

        if (appFeeRefundMinor > 0) {
            String chargeId = refund.getCharge();
            Charge charge = stripeClient.charges().retrieve(chargeId);
            String appFeeId = charge.getApplicationFee();
            if (appFeeId == null) {
                // Direct charge or test mode without a fee — skip silently.
                log.warn("[stripe-refund] charge {} has no application_fee — skipping fee refund (amount={})",
                        chargeId, appFeeRefundMinor);
            } else {
                ApplicationFeeRefundCreateParams feeParams =
                    ApplicationFeeRefundCreateParams.builder().setAmount(appFeeRefundMinor).build();
                RequestOptions feeOpts = RequestOptions.builder()
                    .setIdempotencyKey(idempotencyKey + "_fee").build();
                stripeClient.applicationFees().refunds().create(appFeeId, feeParams, feeOpts);
                log.info("[stripe-refund] created app-fee refund on fee={} amount={}",
                        appFeeId, appFeeRefundMinor);
            }
        }
        return refund;
    }
}

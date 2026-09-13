package com.imin.iminapi.stripe;

import com.imin.iminapi.refund.RefundReason;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Wrapper around the Stripe SDK refund API. ONE Stripe call per logical refund.
 *
 * <p>On a destination charge of {@code G} with {@code application_fee_amount = F} the
 * connected account is credited {@code G − F} net of the fee. A refund of {@code A} with
 * {@code reverse_transfer=true} pays the buyer {@code A} from the platform and pulls the
 * GROSS {@code A} off the connected balance — not {@code A·(G−F)/G}. So
 * {@code refund_application_fee=true} rides along: Stripe returns {@code F·A/G} of the fee
 * to the connected account, leaving the organizer down exactly its own net {@code A·(G−F)/G}
 * and the platform down its own fee share. The flag is PROPORTIONAL, not all-or-nothing —
 * a partial refund refunds a proportional slice of the fee.
 *
 * <p>{@code reverseTransfer=false} is the platform-funded escape hatch: when the connected
 * account's balance cannot cover the reversal, the refund is paid entirely from the platform
 * balance, the connected account is untouched, and
 * {@code PostEventPayoutService.recoverPlatformFundedRefunds} later reverses
 * {@code A − F·A/G} off the charge's destination transfer. The fee flag is {@code false}
 * there: refunding the fee would hand the connected account a share that the organizer-only
 * reversal never claws back.
 */
@Service
public class StripeRefundService {

    private static final Logger log = LoggerFactory.getLogger(StripeRefundService.class);

    private final StripeClient stripeClient;

    public StripeRefundService(StripeClient stripeClient) {
        this.stripeClient = stripeClient;
    }

    /**
     * @param appFeeRefundMinor imin's own estimate of the fee share, {@code round(F·A/G)}. Not
     *                          sent to Stripe — Stripe computes its own proportional number from
     *                          {@code refund_application_fee} — but logged and persisted for the
     *                          payout net, so the two can differ by a minor unit on a partial. Zero
     *                          also suppresses {@code refund_application_fee}: there is no fee to return.
     * @param reverseTransfer   {@code false} funds the refund from the PLATFORM balance and also
     *                          leaves the application fee with the platform for the payout sweep
     *                          to settle; {@code true} reverses the gross and refunds the fee share.
     * @return the Stripe Refund object (with {@code id}, {@code charge}, {@code status} populated).
     * @throws StripeException unwrapped — caller maps to ApiException.
     */
    public Refund create(String paymentIntentId, long amountMinor, String currency,
                         RefundReason reason, long appFeeRefundMinor,
                         boolean reverseTransfer, String idempotencyKey) throws StripeException {

        RefundCreateParams.Builder pb = RefundCreateParams.builder()
            .setPaymentIntent(paymentIntentId)
            .setAmount(amountMinor)
            .setReverseTransfer(reverseTransfer)
            // Stripe rejects the flag on a charge that carries no application fee.
            .setRefundApplicationFee(reverseTransfer && appFeeRefundMinor > 0);
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
        log.info("[stripe-refund] created id={} status={} amount={} {} feeShare={} reverseTransfer={} idemp={}",
                refund.getId(), refund.getStatus(), amountMinor, currency, appFeeRefundMinor,
                reverseTransfer, idempotencyKey);
        return refund;
    }
}

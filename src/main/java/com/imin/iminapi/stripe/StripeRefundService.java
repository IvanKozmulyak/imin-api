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
 * platform holds {@code F} and the connected account holds {@code G − F}. A refund of
 * {@code A} with {@code reverse_transfer=true} debits {@code A} from the platform and
 * reverses {@code A·(G−F)/G} back to it, leaving the platform fee at {@code F·(1 − A/G)}
 * and the organizer bearing its own proportional share. Buyer, platform and organizer are
 * all made whole by that single call — an additional {@code ApplicationFee.Refund} would
 * move a further {@code F·A/G} from the platform to the connected account, i.e. credit the
 * organizer the platform fee twice.
 *
 * <p>{@code refund_application_fee=false} stays: the proportional reversal above is the
 * whole mechanism, and Stripe's all-or-nothing flag would refund the entire fee on a
 * partial refund.
 *
 * <p>{@code reverseTransfer=false} is the platform-funded escape hatch: when the connected
 * account's balance cannot cover the reversal, the refund is paid from the platform balance
 * and recovered from the org's next payout (see {@code RefundService}).
 */
@Service
public class StripeRefundService {

    private static final Logger log = LoggerFactory.getLogger(StripeRefundService.class);

    private final StripeClient stripeClient;

    public StripeRefundService(StripeClient stripeClient) {
        this.stripeClient = stripeClient;
    }

    /**
     * @param appFeeRefundMinor the platform-fee share attributable to this refund. Not sent
     *                          to Stripe — the proportional transfer reversal already applies
     *                          it — but logged and persisted for the payout net.
     * @param reverseTransfer   {@code false} funds the refund from the PLATFORM balance.
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
        log.info("[stripe-refund] created id={} status={} amount={} {} feeShare={} reverseTransfer={} idemp={}",
                refund.getId(), refund.getStatus(), amountMinor, currency, appFeeRefundMinor,
                reverseTransfer, idempotencyKey);
        return refund;
    }
}

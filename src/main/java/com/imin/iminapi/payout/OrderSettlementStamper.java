package com.imin.iminapi.payout;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.stripe.StripeProperties;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.ApplicationFee;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.stripe.param.PaymentIntentRetrieveParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Records what Stripe settled for each paid order of an org: the destination transfer's amount and
 * currency, and the application fee's balance-transaction amount in that currency. One PaymentIntent
 * read per order, once; the payout sweep is the only caller and the only reader of the stamp.
 *
 * <p>Not transactional: each write commits on its own ({@code OrderRepository.stampSettlement}). Any
 * order it cannot read or does not trust stays unstamped, and the payout path refuses to size money on it.
 */
@Component
public class OrderSettlementStamper {

    private static final Logger log = LoggerFactory.getLogger(OrderSettlementStamper.class);

    /**
     * ponytail: at most this many Stripe reads per org per call, newest first; with more than this many orders
     * Stripe keeps refusing, the oldest beyond the page are never re-read until someone stamps or fixes them.
     */
    static final int BATCH = 500;

    private final StripeClient stripeClient;
    private final StripeProperties props;
    private final OrderRepository orders;

    public OrderSettlementStamper(StripeClient stripeClient, StripeProperties props, OrderRepository orders) {
        this.stripeClient = stripeClient;
        this.props = props;
        this.orders = orders;
    }

    /**
     * Stamp the org's unstamped paid orders of the running key's mode. Never throws: the payout and the
     * recoveries that follow refuse to size anything on an order left unstamped.
     */
    public void stampOrg(Organization org) {
        List<Object[]> rows;
        try {
            rows = orders.findUnstampedPaidByOrgId(org.getId(), !props.isLiveKey(), PageRequest.of(0, BATCH));
        } catch (RuntimeException e) {
            log.error("[settlement] could not list the unstamped orders of org {} — nothing stamped this tick",
                    org.getId(), e);
            return;
        }
        Instant stuckBefore = Instant.now().minus(Duration.ofDays(Math.max(0, props.getPayoutBufferDays())));
        for (Object[] row : rows) {
            UUID orderId = (UUID) row[0];
            String piId = (String) row[1];
            Instant createdAt = (Instant) row[2];
            try {
                stampOne(orderId, piId, createdAt != null && createdAt.isBefore(stuckBefore));
            } catch (RuntimeException e) {
                // A malformed answer or an SDK bug on one order must not stop the others, nor the payout.
                log.error("[settlement] reading order {} (pi {}) failed — not stamped; retrying next tick",
                        orderId, piId, e);
            }
        }
    }

    /** {@code stuck}: the order is older than the payout buffer, so its event's payout is overdue. */
    private void stampOne(UUID orderId, String piId, boolean stuck) {
        PaymentIntent pi;
        try {
            pi = stripeClient.paymentIntents().retrieve(piId, PaymentIntentRetrieveParams.builder()
                    .addExpand("latest_charge.balance_transaction")
                    .addExpand("latest_charge.transfer")
                    .addExpand("latest_charge.application_fee")
                    .addExpand("latest_charge.application_fee.balance_transaction")
                    .build());
        } catch (StripeException e) {
            if (stuck) {
                log.error("[settlement] could not read payment intent {} for order {}, older than the payout "
                        + "buffer — {}; its event's payout is held until it is stamped", piId, orderId, e.getCode());
            } else {
                log.warn("[settlement] could not read payment intent {} for order {} — {}; retrying next tick",
                        piId, orderId, e.getCode());
            }
            return;
        }
        Charge ch = pi.getLatestChargeObject();
        Transfer tr = ch == null ? null : ch.getTransferObject();
        if (tr == null || tr.getAmount() == null || tr.getCurrency() == null) {
            log.error("[settlement] order {} (pi {}) has no destination transfer on its latest charge — not stamped; "
                    + "its event cannot be paid out until it is reconciled", orderId, piId);
            return;
        }
        String currency = tr.getCurrency().toLowerCase(Locale.ROOT);
        BalanceTransaction chargeTxn = ch.getBalanceTransactionObject();
        if (chargeTxn == null || !currency.equalsIgnoreCase(chargeTxn.getCurrency())) {
            log.error("[settlement] order {} (pi {}) settled in {} but its transfer is in {} — not stamped",
                    orderId, piId, chargeTxn == null ? null : chargeTxn.getCurrency(), currency);
            return;
        }
        long gross = tr.getAmount();
        long fee = 0L;
        Long chargeFee = ch.getApplicationFeeAmount();
        if (chargeFee != null && chargeFee != 0L) {
            // The fee object carries the presentment amount; what the platform kept is its balance transaction.
            ApplicationFee af = ch.getApplicationFeeObject();
            BalanceTransaction feeTxn = af == null ? null : af.getBalanceTransactionObject();
            if (feeTxn == null || feeTxn.getAmount() == null || !currency.equalsIgnoreCase(feeTxn.getCurrency())) {
                log.error("[settlement] order {} (pi {}) has an application fee not settled in its transfer "
                        + "currency {} ({}) — not stamped", orderId, piId, currency,
                        feeTxn == null ? null : feeTxn.getCurrency());
                return;
            }
            fee = feeTxn.getAmount();
        }
        if (gross < 0L || fee < 0L || fee > gross) {
            log.error("[settlement] order {} (pi {}) settled gross {} fee {} {} — out of range, not stamped",
                    orderId, piId, gross, fee, currency);
            return;
        }
        try {
            int n = orders.stampSettlement(orderId, currency, gross, fee);
            if (n == 1) {
                log.info("[settlement] order {} stamped {} gross {} fee {} (rate {})", orderId, currency, gross, fee,
                        chargeTxn.getExchangeRate());
            }
        } catch (RuntimeException e) {
            // No money moved: the next tick reads the order again.
            log.error("[settlement] could not write the stamp for order {} — {}", orderId, e.toString(), e);
        }
    }
}

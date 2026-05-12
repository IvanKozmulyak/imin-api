package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.TicketTierRepository;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Product;
import com.stripe.param.ProductCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Syncs a {@link TicketTier} to a Stripe Product + Price on the *platform* account.
 *
 * <p><b>Why platform-level, not on the connected account?</b> Destination Charges keep the funds
 * flow on the platform PaymentIntent; the line item references a platform price id, and Stripe
 * routes the net proceeds to {@code transfer_data.destination}. Creating a Product on the
 * connected account would force us to use Direct Charges with stripeAccount headers — a different
 * architecture entirely.
 *
 * <p><b>Price immutability trade-off.</b> Stripe Prices cannot be mutated; if the tier's
 * {@code priceMinor} changes, we re-create the Product (with a fresh default Price). This wastes
 * one Product per price change but keeps the implementation simple and avoids dangling-archived-
 * price bugs. Acceptable for the sample; revisit if churn becomes a problem.
 */
@Service
public class StripeProductService {

    private static final Logger log = LoggerFactory.getLogger(StripeProductService.class);

    private final StripeClient stripeClient;
    private final TicketTierRepository tiers;

    public StripeProductService(StripeClient stripeClient, TicketTierRepository tiers) {
        this.stripeClient = stripeClient;
        this.tiers = tiers;
    }

    /**
     * Best-effort sync. Logs and swallows failures so tier persistence is never blocked by a
     * Stripe outage. Callers should invoke this AFTER the tier has been saved.
     */
    public void syncTier(TicketTier tier, Event event) {
        try {
            doSync(tier, event);
        } catch (StripeException e) {
            log.warn("Stripe product sync failed for tier {} (event {}): {} — continuing without product",
                    tier.getId(), event.getId(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Unexpected error syncing Stripe product for tier {} (event {}): {}",
                    tier.getId(), event.getId(), e.getMessage(), e);
        }
    }

    private void doSync(TicketTier tier, Event event) throws StripeException {
        String currency = event.getCurrency() == null ? "usd" : event.getCurrency().toLowerCase();
        long unitAmount = tier.getPriceMinor();
        String description = "Ticket for " + event.getName();

        // Decide create-vs-update. We re-create when:
        //   (a) the tier has no product yet, OR
        //   (b) the price has changed (Stripe Prices are immutable — see class doc).
        boolean needsCreate = tier.getStripeProductId() == null || tier.getStripePriceId() == null
                || priceHasChanged(tier, currency);

        if (needsCreate) {
            ProductCreateParams params = ProductCreateParams.builder()
                    .setName(tier.getName())
                    .setDescription(description)
                    // default_price_data — single-shot Product+Price create. The returned Product has its
                    // defaultPrice set to the new Price id, which is what we cache locally.
                    .setDefaultPriceData(ProductCreateParams.DefaultPriceData.builder()
                            .setCurrency(currency)
                            .setUnitAmount(unitAmount)
                            .build())
                    // metadata is searchable in the Stripe dashboard — handy for support debugging.
                    .putMetadata("tierId", tier.getId().toString())
                    .putMetadata("eventId", event.getId().toString())
                    .putMetadata("orgId", event.getOrgId().toString())
                    .build();

            // Platform-level products live under the V1 /products surface (stripeClient.products()).
            // V2 has no Products primitive — products+prices are still the V1 model.
            Product product = stripeClient.products().create(params);

            tier.setStripeProductId(product.getId());
            tier.setStripePriceId(product.getDefaultPrice());
            tiers.save(tier);
            log.debug("Created Stripe product {} (price {}) for tier {}",
                    product.getId(), product.getDefaultPrice(), tier.getId());
            return;
        }

        // Name/description only — no price change. Mutate the existing product in place.
        var updateParams = com.stripe.param.ProductUpdateParams.builder()
                .setName(tier.getName())
                .setDescription(description)
                .putMetadata("tierId", tier.getId().toString())
                .putMetadata("eventId", event.getId().toString())
                .putMetadata("orgId", event.getOrgId().toString())
                .build();
        stripeClient.products().update(tier.getStripeProductId(), updateParams);
        log.debug("Updated Stripe product {} for tier {}", tier.getStripeProductId(), tier.getId());
    }

    /**
     * Best-effort: fetch the existing Price and compare amount + currency. If we can't read it
     * (e.g. price was archived externally) treat as "changed" so we re-create.
     */
    private boolean priceHasChanged(TicketTier tier, String currency) {
        try {
            var price = stripeClient.prices().retrieve(tier.getStripePriceId());
            Long amount = price.getUnitAmount();
            String existingCurrency = price.getCurrency();
            return amount == null
                    || amount != tier.getPriceMinor()
                    || !currency.equalsIgnoreCase(existingCurrency);
        } catch (StripeException e) {
            log.debug("Could not retrieve existing Stripe price {} for tier {}: {} — treating as changed",
                    tier.getStripePriceId(), tier.getId(), e.getMessage());
            return true;
        }
    }

    /** @return metadata snapshot for logging in tests / debug. */
    Map<String, String> describe(TicketTier tier) {
        return Map.of(
                "productId", String.valueOf(tier.getStripeProductId()),
                "priceId", String.valueOf(tier.getStripePriceId()));
    }
}

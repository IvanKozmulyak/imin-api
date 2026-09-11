package com.imin.iminapi.stripe;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Compares what Stripe actually charged against the total imin stamped onto the checkout
 * when the buyer agreed to it, so fulfilment can refuse an amount it never priced.
 *
 * <p>The stamp — {@link #EXPECTED_TOTAL_MINOR} / {@link #EXPECTED_CURRENCY}, written by
 * {@code StripeCheckoutService.reserveAndBuildMetadata} onto both the Session and the
 * PaymentIntent — is the reference, NOT a recomputation from the live tier price. A session
 * can stay open for 30 minutes (7 days on an async method), and an organizer price or promo
 * edit inside that window would otherwise turn a perfectly good payment into a refusal.
 *
 * <p>Pure read: no Stripe call, no repository, no writes. A checkout created before the stamp
 * existed is {@link Result#checked() unchecked} rather than a mismatch — refusing there would
 * be a paid buyer with no ticket.
 */
@Component
public class CheckoutAmountVerifier {

    /** Metadata key: the total (tickets after discount + fee) the buyer was quoted, in minor units. */
    public static final String EXPECTED_TOTAL_MINOR = "expected_total_minor";
    /** Metadata key: the currency that total was quoted in, lowercase. */
    public static final String EXPECTED_CURRENCY = "expected_currency";

    /**
     * @param checked          false when there is no stamp to compare against; the caller must
     *                         fulfil anyway and log why.
     * @param match            true when the charged amount (and, when stamped, the currency)
     *                         equal the stamped ones.
     * @param expectedMinor    the stamped total, or null when {@code checked} is false.
     * @param expectedCurrency the stamped currency, or null when it was not stamped.
     * @param skipReason       why the check was skipped, or null when it ran.
     */
    public record Result(boolean checked, boolean match, Long expectedMinor,
                         String expectedCurrency, String skipReason) {

        static Result skipped(String reason) {
            return new Result(false, true, null, null, reason);
        }

        static Result of(long expectedMinor, String expectedCurrency, boolean match) {
            return new Result(true, match, expectedMinor, expectedCurrency, null);
        }
    }

    /**
     * @param metadata       the PaymentIntent's metadata, as stamped by
     *                       {@code StripeCheckoutService.reserveAndBuildMetadata}.
     * @param actualMinor    the amount Stripe actually charged, in minor units.
     * @param actualCurrency the currency Stripe actually charged in.
     */
    public Result verify(Map<String, String> metadata, Long actualMinor, String actualCurrency) {
        if (metadata == null) return Result.skipped("no metadata");
        if (actualMinor == null) return Result.skipped("PaymentIntent carried no amount");

        String rawExpected = metadata.get(EXPECTED_TOTAL_MINOR);
        if (rawExpected == null || rawExpected.isBlank()) {
            return Result.skipped("no " + EXPECTED_TOTAL_MINOR + " stamp — checkout predates it");
        }
        long expected;
        try {
            expected = Long.parseLong(rawExpected.trim());
        } catch (NumberFormatException e) {
            return Result.skipped(EXPECTED_TOTAL_MINOR + " stamp is not a number: " + rawExpected);
        }

        // The currency stamp rides along with the total; when only the total is there the
        // amount is still worth checking — skipping both over a missing key would be worse.
        String expectedCurrency = metadata.get(EXPECTED_CURRENCY);
        boolean currencyMatches = expectedCurrency == null || expectedCurrency.isBlank()
                || expectedCurrency.equalsIgnoreCase(actualCurrency);
        return Result.of(expected, expectedCurrency, expected == actualMinor && currencyMatches);
    }
}

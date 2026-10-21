package com.imin.iminapi.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The crew-link code rides untrusted buyer input into Stripe metadata, whose values Stripe caps at 500 chars. */
class CheckoutAttributionTest {

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "kQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2m"
                    + "kQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2m"
                    + "kQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2m"
                    + "kQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2m"
                    + "kQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2m"
                    + "kQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2mkQ7xP9aZ2m",
            "k7Q2xP9a%20utm_source=wa",
            "   "})
    void aMangledRefNeverReachesStripeMetadata(String mangled) {
        CheckoutAttribution attribution = new CheckoutAttribution("imin", null, null, "anon-1", mangled);
        Map<String, String> metadata = new HashMap<>();

        attribution.putInto(metadata);

        assertThat(attribution.referralCode()).isNull();
        assertThat(metadata).doesNotContainKey(CheckoutAttribution.META_REF)
                .containsEntry(CheckoutAttribution.META_UTM_SOURCE, "imin");
        assertThat(CheckoutAttribution.fromMetadata(Map.of(CheckoutAttribution.META_REF, mangled)).referralCode())
                .isNull();
    }
}

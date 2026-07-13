package com.imin.iminapi.service.audience;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E.164 normalization/validation for the SMS opt-in (§4). Separators are
 * stripped; a leading '+' and 8–15 digits are required; anything else is empty.
 */
class PhoneNormalizerTest {

    @Test
    void stripsSeparatorsAndKeepsPlus() {
        assertThat(PhoneNormalizer.normalize("+380 67 123 45 67")).contains("+380671234567");
        assertThat(PhoneNormalizer.normalize("+38 (067) 123-45-67")).contains("+380671234567");
    }

    @Test
    void rejectsMissingPlus() {
        assertThat(PhoneNormalizer.normalize("380671234567")).isEmpty();
    }

    @Test
    void rejectsTooShortOrTooLong() {
        assertThat(PhoneNormalizer.normalize("+1234567")).isEmpty();          // 7 digits
        assertThat(PhoneNormalizer.normalize("+1234567890123456")).isEmpty(); // 16 digits
    }

    @Test
    void rejectsNonDigitsAfterStripping() {
        assertThat(PhoneNormalizer.normalize("+380abc1234")).isEmpty();
    }

    @Test
    void rejectsNullAndBlank() {
        assertThat(PhoneNormalizer.normalize(null)).isEmpty();
        assertThat(PhoneNormalizer.normalize("   ")).isEmpty();
    }

    @Test
    void acceptsMinAndMaxLengths() {
        assertThat(PhoneNormalizer.normalize("+12345678")).contains("+12345678");         // 8 digits
        assertThat(PhoneNormalizer.normalize("+123456789012345")).contains("+123456789012345"); // 15 digits
    }

    @Test
    void returnValueFitsColumn() {
        Optional<String> ok = PhoneNormalizer.normalize("+380671234567");
        assertThat(ok).isPresent();
        assertThat(ok.get().length()).isLessThanOrEqualTo(20);
    }
}

package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.crypto.CapiTokenCipher;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapiTokenCipherTest {

    // 32-byte (256-bit) key, base64-encoded, for a deterministic test.
    private static final String TEST_KEY_B64 =
            Base64.getEncoder().encodeToString(new byte[]{
                    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                    17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});

    @Test
    void roundTripsPlaintext() {
        CapiTokenCipher cipher = new CapiTokenCipher(TEST_KEY_B64);
        String token = "EAAG_test_capi_access_token_0123456789";
        String enc = cipher.encrypt(token);
        assertThat(enc).isNotEqualTo(token);
        assertThat(cipher.decrypt(enc)).isEqualTo(token);
    }

    @Test
    void producesDistinctCiphertextsForSamePlaintext() {
        CapiTokenCipher cipher = new CapiTokenCipher(TEST_KEY_B64);
        String token = "same-token";
        // Fresh random IV each call → different ciphertext, both decrypt back.
        assertThat(cipher.encrypt(token)).isNotEqualTo(cipher.encrypt(token));
    }

    @Test
    void missingKeyFailsOnFirstUseNotAtBoot() {
        // Lazy contract (2026-07-14): a missing key must never block application
        // boot — construction succeeds, the first encrypt/decrypt throws.
        CapiTokenCipher cipher = new CapiTokenCipher("");
        assertThatThrownBy(() -> cipher.encrypt("tok"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("META_CAPI_ENC_KEY");
        assertThatThrownBy(() -> cipher.decrypt("AAAA"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("META_CAPI_ENC_KEY");
    }

    @Test
    void rejectsTamperedCiphertext() {
        CapiTokenCipher cipher = new CapiTokenCipher(TEST_KEY_B64);
        String enc = cipher.encrypt("token");
        // Flip a character in the base64 body — GCM auth tag must reject it.
        String tampered = enc.substring(0, enc.length() - 2)
                + (enc.endsWith("A") ? "B" : "A") + enc.charAt(enc.length() - 1);
        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }
}

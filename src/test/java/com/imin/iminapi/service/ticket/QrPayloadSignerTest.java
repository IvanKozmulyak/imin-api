package com.imin.iminapi.service.ticket;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QrPayloadSignerTest {

    private QrPayloadSigner signer(String secret) {
        TicketProperties p = new TicketProperties();
        p.setSigningSecret(secret);
        return new QrPayloadSigner(p);
    }

    @Test
    void sign_and_verify_round_trip() {
        QrPayloadSigner s = signer("test-secret-32-bytes-of-entropy-xx");
        String payload = s.sign("abc123token");
        assertTrue(payload.startsWith("imin1."), payload);
        assertEquals(Optional.of("abc123token"), s.verify(payload));
    }

    @Test
    void tampered_token_fails_verification() {
        QrPayloadSigner s = signer("test-secret-32-bytes-of-entropy-xx");
        String payload = s.sign("abc123token");
        String tampered = payload.replace("abc123token", "differentToken");
        assertEquals(Optional.empty(), s.verify(tampered));
    }

    @Test
    void tampered_signature_fails_verification() {
        QrPayloadSigner s = signer("test-secret-32-bytes-of-entropy-xx");
        String payload = s.sign("abc123token");
        char last = payload.charAt(payload.length() - 1);
        char swapped = last == 'A' ? 'B' : 'A';
        String tampered = payload.substring(0, payload.length() - 1) + swapped;
        assertEquals(Optional.empty(), s.verify(tampered));
    }

    @Test
    void missing_version_prefix_is_rejected() {
        QrPayloadSigner s = signer("test-secret-32-bytes-of-entropy-xx");
        assertEquals(Optional.empty(), s.verify("imin0.abc.xyz"));
        assertEquals(Optional.empty(), s.verify("garbage"));
        assertEquals(Optional.empty(), s.verify(null));
        assertEquals(Optional.empty(), s.verify(""));
    }

    @Test
    void different_secret_fails_verification() {
        QrPayloadSigner a = signer("test-secret-32-bytes-of-entropy-xx");
        QrPayloadSigner b = signer("OTHER-secret-32-bytes-of-entropy-x");
        String payload = a.sign("abc123token");
        assertEquals(Optional.empty(), b.verify(payload));
    }

    @Test
    void blank_secret_throws_at_construction() {
        TicketProperties p = new TicketProperties();
        p.setSigningSecret("");
        assertThrows(IllegalStateException.class, () -> new QrPayloadSigner(p));
    }
}

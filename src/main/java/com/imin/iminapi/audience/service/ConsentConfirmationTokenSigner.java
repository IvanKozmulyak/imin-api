package com.imin.iminapi.audience.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * HMAC-signed reference to a {@code consent_confirmation_tokens} row (the row holds expiry and single use). Key
 * {@code IMIN_CONSENT_CONFIRMATION_SECRET}; blank uses the ticket secret, allowed only while emails are off.
 */
@Component
public class ConsentConfirmationTokenSigner {

    static final String PREFIX = "consent-confirm:v1:";

    private final byte[] secret;

    public ConsentConfirmationTokenSigner(
            @Value("${imin.consent-confirmation.secret:}") String own,
            @Value("${imin.ticket.signing-secret:dev-consent-confirm-secret-change-me-32b}") String fallback,
            @Value("${imin.audience-plan.consent-confirmation-emails-enabled:false}") boolean emailsEnabled) {
        boolean ownSet = own != null && !own.isBlank();
        // Once links are really emailed they must not share the ticket key: refuse to start without our own.
        if (!ownSet && emailsEnabled) {
            throw new IllegalStateException("IMIN_CONSENT_CONFIRMATION_SECRET must be set when"
                    + " IMIN_CONSENT_CONFIRMATION_EMAILS_ENABLED is true");
        }
        this.secret = (ownSet ? own : fallback).getBytes(StandardCharsets.UTF_8);
    }

    public String sign(UUID tokenId) {
        String p = base64((PREFIX + tokenId).getBytes(StandardCharsets.UTF_8));
        return p + "." + base64(hmac(p));
    }

    /** The row id when the signature is ours and the payload well formed; empty otherwise. */
    public Optional<UUID> verify(String token) {
        if (token == null || token.isBlank() || token.length() > 512) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.') || dot == token.length() - 1) return Optional.empty();
        String p = token.substring(0, dot);
        byte[] expected = base64(hmac(p)).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, token.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(p), StandardCharsets.UTF_8);
            if (!payload.startsWith(PREFIX)) return Optional.empty();
            return Optional.of(UUID.fromString(payload.substring(PREFIX.length())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    private static String base64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}

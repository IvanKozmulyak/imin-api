package com.imin.iminapi.service.ticket;

import org.springframework.stereotype.Service;

/**
 * Apple Wallet pass generation. Returns false from {@link #isConfigured()}
 * until the {@code APPLE_WALLET_*} env vars are wired and jpasskit is added —
 * see Task 12 of the ticket-issuance plan.
 *
 * <p>Callers gate on {@link #isConfigured()} before linking to or invoking
 * {@link #generatePass(String)} so the email can degrade gracefully (no
 * "Add to Apple Wallet" button when certs are missing) and the public
 * endpoint can respond 503.
 */
@Service
public class AppleWalletPassService {

    public boolean isConfigured() {
        return false;
    }

    public byte[] generatePass(String ticketToken) {
        throw new IllegalStateException("Apple Wallet not configured");
    }
}

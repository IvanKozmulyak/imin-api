package com.imin.iminapi.service.ticket.google;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Google Wallet Event Ticket configuration. Independent of
 * {@code imin.apple-wallet} in every direction: either wallet can be live
 * without the other, and neither gate can affect checkout, issuance, email or
 * the door.
 *
 * <p><b>Every field has a Java default on purpose.</b>
 * {@code src/test/resources/application.yaml} <b>replaces</b> the main YAML
 * rather than merging with it and carries no {@code imin.google-wallet} block,
 * so a value that exists only in the main file is invisible to every test.
 */
@ConfigurationProperties(prefix = "imin.google-wallet")
public class GoogleWalletProperties {

    /**
     * Master switch — and it defaults to FALSE, unlike
     * {@code imin.apple-wallet.enabled}.
     *
     * <p>The asymmetry is deliberate. A new Google issuer account is in demo
     * mode: passes are only deliverable to accounts holding Admin/Developer on
     * the issuer or explicitly added as testers, and they carry a
     * {@code [TEST ONLY]} prefix. Publishing access is a separate review that
     * <b>cannot be requested until at least one Passes Class exists</b> — i.e.
     * until this code has already run against production Google.
     *
     * <p>So there is a window where the credentials are correct, the endpoint
     * works, and the pass reaches nobody. With an enabled-by-default switch,
     * setting the issuer id to get through stage one would light the CTA for
     * every real buyer during exactly that window, and it would fail looking
     * like a broken button rather than an unapproved account. Default false
     * makes turning it on the last deliberate step after approval lands.
     */
    private boolean enabled = false;

    /** Issuer ID from the Google Pay &amp; Wallet Console (a long numeric string). Blank ⇒ off. */
    private String issuerId = "";

    /**
     * The service-account JSON key, base64-encoded.
     *
     * <p>Base64 for the same reason the Apple p12 is: the raw file is multi-line
     * JSON containing a PEM block with embedded {@code \n} escapes, and every
     * layer between a dashboard field and {@code System.getenv} mangles at least
     * one of them. A single base64 blob has no escaping to get wrong.
     * {@link GoogleServiceAccountKey#parseBase64(String)} also accepts the raw
     * JSON, because pasting that is a reasonable mistake and it should not look
     * like a corrupt credential.
     */
    private String serviceAccountJsonBase64 = "";

    /**
     * Approved domains the save link may be initiated from.
     *
     * <p>Google's JWT docs warn that the Add-to-Google-Wallet button "will not
     * render when the origins field is not defined", failing as
     * {@code X-Frame-Options} / "Refused to display". That warning is about the
     * embeddable JS button rather than the plain 302 this service uses, so it
     * may not bite here — but shipping the field undefined in production is
     * betting on a distinction the docs do not make for us. Blank is the local
     * dev default; production sets it.
     */
    private List<String> origins = List.of();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getIssuerId() { return issuerId; }
    public void setIssuerId(String v) { this.issuerId = v; }
    public String getServiceAccountJsonBase64() { return serviceAccountJsonBase64; }
    public void setServiceAccountJsonBase64(String v) { this.serviceAccountJsonBase64 = v; }
    public List<String> getOrigins() { return origins; }

    /**
     * Blank means "no origins", never "one origin that is the empty string".
     * Spring binds {@code origins: ${GOOGLE_WALLET_ORIGINS:}} through here, and
     * an unset env var arrives as a single empty element on some binder paths —
     * which would put an origin on the token that Google can never match.
     */
    public void setOrigins(List<String> v) {
        if (v == null) {
            this.origins = List.of();
            return;
        }
        List<String> cleaned = new ArrayList<>();
        for (String s : v) {
            if (s != null && !s.isBlank()) {
                cleaned.add(s.trim());
            }
        }
        this.origins = List.copyOf(cleaned);
    }

    /** True when a save link can be minted. */
    public boolean fullyConfigured() {
        return enabled && !isBlank(issuerId) && !isBlank(serviceAccountJsonBase64);
    }

    /**
     * Why the gate is closed, in the operator's own vocabulary — the env var
     * names, not the field names. Empty when it is open.
     *
     * <p><b>This exists because of what happened on the Apple side.</b>
     * {@code certPassword} sat in {@code fullyConfigured()} for months, so a
     * perfectly good passwordless {@code .p12} produced a permanent 503 while
     * the system reported "not configured" — a true statement about a false
     * cause, with nothing to distinguish it from "nobody has set this up yet".
     * A gate that closes must say which value closed it, and the demo-mode
     * window (credentials correct, {@code enabled} still false) is the state
     * most likely to be mistaken for a fault.
     */
    public Optional<String> gateReason() {
        if (fullyConfigured()) {
            return Optional.empty();
        }
        List<String> missing = new ArrayList<>();
        if (isBlank(issuerId)) {
            missing.add("GOOGLE_WALLET_ISSUER_ID is blank");
        }
        if (isBlank(serviceAccountJsonBase64)) {
            missing.add("GOOGLE_WALLET_SERVICE_ACCOUNT_JSON_BASE64 is blank");
        }
        if (!enabled) {
            missing.add(missing.isEmpty()
                    // Credentials are complete: this is the demo-mode window,
                    // and it is the expected state until publishing access is
                    // granted. Say that, so nobody "fixes" it early.
                    ? "GOOGLE_WALLET_ENABLED is false — credentials are complete, so this is "
                            + "the demo-mode hold: flip it only once Google has granted "
                            + "publishing access, which cannot be requested until a class exists"
                    : "GOOGLE_WALLET_ENABLED is false");
        }
        return Optional.of(String.join("; ", missing));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

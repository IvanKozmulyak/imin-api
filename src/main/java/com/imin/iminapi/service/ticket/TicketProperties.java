package com.imin.iminapi.service.ticket;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for ticket issuance / recovery / QR signing.
 *
 * <p>{@code signing-secret} is required at boot for the QR signer to construct;
 * the app fails fast there if it's blank. The recovery knobs default to spec-defined
 * values.
 */
@ConfigurationProperties(prefix = "imin.ticket")
public class TicketProperties {
    private String signingSecret = "";
    private int recoveryWindowDays = 90;
    private int recoveryMaxPerHour = 5;
    /**
     * The API's own public base URL. Used to build absolute QR / pkpass links
     * for emails and the ticket detail response so the buyer's email client
     * can reach them. Set via IMIN_API_PUBLIC_BASE_URL. Distinct from
     * imin.email.buyer-site-base-url (the buyer site, which serves the ticket
     * HTML page) and imin.email.app-base-url (the organizer dashboard).
     *
     * <p><b>Defaults to production, not localhost</b> (2026-08-16). It defaulted
     * to {@code http://localhost:8080} until then, which meant an unset
     * IMIN_API_PUBLIC_BASE_URL put a link to the recipient's own machine in
     * every ticket email — the same failure as the {@code /optout} unsubscribe
     * URL, and just as invisible, because the mail renders fine either way.
     * {@code imin.marketing.api-public-base-url} reads the same env var and has
     * defaulted to production since 2026-07-22; the two now agree. Local dev
     * sets the localhost value explicitly in {@code application-dev.yaml}.
     */
    private String apiPublicBaseUrl = "https://api.imin.wtf";

    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }
    public int getRecoveryWindowDays() { return recoveryWindowDays; }
    public void setRecoveryWindowDays(int recoveryWindowDays) { this.recoveryWindowDays = recoveryWindowDays; }
    public int getRecoveryMaxPerHour() { return recoveryMaxPerHour; }
    public void setRecoveryMaxPerHour(int recoveryMaxPerHour) { this.recoveryMaxPerHour = recoveryMaxPerHour; }
    public String getApiPublicBaseUrl() { return apiPublicBaseUrl; }
    public void setApiPublicBaseUrl(String apiPublicBaseUrl) { this.apiPublicBaseUrl = apiPublicBaseUrl; }
}

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
     * can reach them. Set via IMIN_API_PUBLIC_BASE_URL; defaults to localhost
     * for dev. Distinct from imin.email.buyer-site-base-url (the buyer site,
     * which serves the ticket HTML page) and imin.email.app-base-url (the
     * organizer dashboard).
     */
    private String apiPublicBaseUrl = "http://localhost:8080";

    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }
    public int getRecoveryWindowDays() { return recoveryWindowDays; }
    public void setRecoveryWindowDays(int recoveryWindowDays) { this.recoveryWindowDays = recoveryWindowDays; }
    public int getRecoveryMaxPerHour() { return recoveryMaxPerHour; }
    public void setRecoveryMaxPerHour(int recoveryMaxPerHour) { this.recoveryMaxPerHour = recoveryMaxPerHour; }
    public String getApiPublicBaseUrl() { return apiPublicBaseUrl; }
    public void setApiPublicBaseUrl(String apiPublicBaseUrl) { this.apiPublicBaseUrl = apiPublicBaseUrl; }
}

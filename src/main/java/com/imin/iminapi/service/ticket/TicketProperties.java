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

    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }
    public int getRecoveryWindowDays() { return recoveryWindowDays; }
    public void setRecoveryWindowDays(int recoveryWindowDays) { this.recoveryWindowDays = recoveryWindowDays; }
    public int getRecoveryMaxPerHour() { return recoveryMaxPerHour; }
    public void setRecoveryMaxPerHour(int recoveryMaxPerHour) { this.recoveryMaxPerHour = recoveryMaxPerHour; }
}

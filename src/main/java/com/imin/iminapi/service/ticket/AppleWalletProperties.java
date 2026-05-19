package com.imin.iminapi.service.ticket;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Apple Wallet pkpass-signing configuration. All five are required for
 * {@link AppleWalletPassService#isConfigured()} to return true. When any is
 * blank, the pass endpoint returns 503 and the email template suppresses the
 * Wallet CTA — so missing certs degrade gracefully rather than break.
 *
 * <p>{@code certP12Base64} and {@code wwdrPemBase64} are base64-encoded because
 * env vars can't safely carry raw binary cert bytes.
 */
@ConfigurationProperties(prefix = "imin.apple-wallet")
public class AppleWalletProperties {
    private String passTypeId = "";
    private String teamId = "";
    private String certP12Base64 = "";
    private String certPassword = "";
    private String wwdrPemBase64 = "";

    public String getPassTypeId() { return passTypeId; }
    public void setPassTypeId(String v) { this.passTypeId = v; }
    public String getTeamId() { return teamId; }
    public void setTeamId(String v) { this.teamId = v; }
    public String getCertP12Base64() { return certP12Base64; }
    public void setCertP12Base64(String v) { this.certP12Base64 = v; }
    public String getCertPassword() { return certPassword; }
    public void setCertPassword(String v) { this.certPassword = v; }
    public String getWwdrPemBase64() { return wwdrPemBase64; }
    public void setWwdrPemBase64(String v) { this.wwdrPemBase64 = v; }

    public boolean fullyConfigured() {
        return !isBlank(passTypeId)
                && !isBlank(teamId)
                && !isBlank(certP12Base64)
                && !isBlank(certPassword)
                && !isBlank(wwdrPemBase64);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

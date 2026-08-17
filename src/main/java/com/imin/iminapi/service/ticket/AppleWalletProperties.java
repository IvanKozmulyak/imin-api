package com.imin.iminapi.service.ticket;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Apple Wallet pkpass-signing configuration. The four credential values
 * ({@code passTypeId}, {@code teamId}, {@code certP12Base64},
 * {@code wwdrPemBase64}) plus {@code enabled} are what
 * {@link AppleWalletPassService#isConfigured()} gates on. When any is blank the
 * pass endpoint returns 503 and the email template suppresses the Wallet CTA —
 * so missing certs degrade gracefully rather than break.
 *
 * <p>{@code certP12Base64} and {@code wwdrPemBase64} are base64-encoded because
 * env vars can't safely carry raw binary cert bytes.
 */
@ConfigurationProperties(prefix = "imin.apple-wallet")
public class AppleWalletProperties {
    /**
     * Master switch. Defaults true so setting the four credential values below
     * is sufficient to turn passes on; it exists so an incident can disable
     * pass generation without deleting a certificate out of the environment
     * (and losing the only copy of it).
     *
     * <p>The default lives here, in Java, on purpose: {@code
     * src/test/resources/application.yaml} <b>replaces</b> the main YAML rather
     * than merging with it and carries no {@code imin.apple-wallet} block at
     * all, so a value that exists only in the main YAML is invisible to every
     * test.
     */
    private boolean enabled = true;
    private String passTypeId = "";
    private String teamId = "";
    private String certP12Base64 = "";
    private String certPassword = "";
    private String wwdrPemBase64 = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
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

    /**
     * The export password for the PKCS#12, never null — an absent password is
     * the empty string, which is what an empty-password P12 actually wants and
     * what jpasskit's {@code keyStorePassword.toCharArray()} requires (it NPEs
     * on null).
     */
    public String certPasswordOrEmpty() {
        return certPassword == null ? "" : certPassword;
    }

    /**
     * True when a pass can be signed.
     *
     * <p><b>{@code certPassword} is deliberately NOT required.</b> A PKCS#12
     * exported with an empty password is legal and common
     * ({@code openssl pkcs12 -export -passout pass:}), and demanding one here
     * meant a correct certificate produced a permanent, undiagnosable 503:
     * indistinguishable from "not configured yet", with no log line either way.
     * A blank password is handed to the keystore as an empty {@code char[]},
     * which is exactly what an empty-password P12 expects.
     */
    public boolean fullyConfigured() {
        return enabled
                && !isBlank(passTypeId)
                && !isBlank(teamId)
                && !isBlank(certP12Base64)
                && !isBlank(wwdrPemBase64);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

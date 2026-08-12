package com.imin.iminapi.buyer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration for the buyer-account surface ({@code /api/v1/buyer/**}).
 *
 * <p>{@link #allowedOrigins} is deliberately an <b>exact-string</b> list with no
 * wildcards, and it is used twice: as the CORS allow-list registered for the
 * buyer namespace, and as the {@code Origin} check that makes the platform-wide
 * {@code csrf.disable()} survivable now that a cookie is a credential (epic
 * §2.6). The platform list {@code imin.cors.allowed-origin-patterns} must not be
 * reused here — it contains {@code https://imin-public-*.vercel.app} alongside
 * {@code allowCredentials(true)}, and Vercel project names are first-come.
 */
@ConfigurationProperties(prefix = "imin.buyer")
public class BuyerProperties {

    private List<String> allowedOrigins = List.of("http://localhost:3000", "https://app.imin.wtf");

    /** Absolute session lifetime. Long on purpose (§2.5) — sessions are revocable. */
    private int sessionTtlDays = 180;

    /** Idle lifetime, enforced against {@code last_used_at}. */
    private int sessionIdleDays = 90;

    /** How long a revoked session row is kept before the sweeper deletes it. */
    private int revokedSessionRetentionDays = 30;

    /** Unverified {@code buyer_account_emails} rows expire after this (§2.3 rule 2). */
    private int unverifiedEmailTtlHours = 72;

    /** Retention for the DB-counted verification-attempt rows. */
    private int verificationAttemptRetentionHours = 24;

    /**
     * Pepper for {@code buyer_email_verification_codes.code_hash}
     * ({@code IMIN_BUYER_CODE_SECRET}). A bare SHA-256 of a six-digit code is an
     * offline dictionary of one million entries, so the digest is
     * {@code HMAC-SHA256(secret, code)} (§2.2, and open question 4 settled that
     * way in §15 — the secret is already set in production).
     *
     * <p>Blank fails startup under the {@code prod} profile; elsewhere it
     * degrades to an ephemeral per-instance key with a warning, exactly as
     * {@code OAuthStateService} does for its own HMAC key.
     */
    private String codeSecret = "";

    /** Verification-code lifetime. Matches the organizer code's 10 minutes. */
    private int verificationCodeTtlMinutes = 10;

    /** Wrong guesses allowed against one code, mirroring {@code chk_bevc_attempts_range}. */
    private int verificationMaxAttempts = 5;

    /**
     * DB-counted per-address lockout (§2.2): this many failed verifications for
     * one {@code email_normalized} inside {@link #lockoutWindowMinutes} locks
     * that address for the rest of the window, no matter how many fresh codes
     * were issued. Separate from the bucket4j resend limit precisely because
     * {@code RateLimitConfig} is {@code @Profile("!test")}.
     */
    private int lockoutFailureThreshold = 10;

    private int lockoutWindowMinutes = 60;

    /** Password-reset token lifetime. Matches {@code PasswordResetService.TOKEN_TTL}. */
    private int passwordResetTtlMinutes = 30;

    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }

    public int getSessionTtlDays() { return sessionTtlDays; }
    public void setSessionTtlDays(int sessionTtlDays) { this.sessionTtlDays = sessionTtlDays; }

    public int getSessionIdleDays() { return sessionIdleDays; }
    public void setSessionIdleDays(int sessionIdleDays) { this.sessionIdleDays = sessionIdleDays; }

    public int getRevokedSessionRetentionDays() { return revokedSessionRetentionDays; }
    public void setRevokedSessionRetentionDays(int revokedSessionRetentionDays) {
        this.revokedSessionRetentionDays = revokedSessionRetentionDays;
    }

    public int getUnverifiedEmailTtlHours() { return unverifiedEmailTtlHours; }
    public void setUnverifiedEmailTtlHours(int unverifiedEmailTtlHours) {
        this.unverifiedEmailTtlHours = unverifiedEmailTtlHours;
    }

    public int getVerificationAttemptRetentionHours() { return verificationAttemptRetentionHours; }
    public void setVerificationAttemptRetentionHours(int verificationAttemptRetentionHours) {
        this.verificationAttemptRetentionHours = verificationAttemptRetentionHours;
    }

    public String getCodeSecret() { return codeSecret; }
    public void setCodeSecret(String codeSecret) { this.codeSecret = codeSecret; }

    public int getVerificationCodeTtlMinutes() { return verificationCodeTtlMinutes; }
    public void setVerificationCodeTtlMinutes(int v) { this.verificationCodeTtlMinutes = v; }

    public int getVerificationMaxAttempts() { return verificationMaxAttempts; }
    public void setVerificationMaxAttempts(int v) { this.verificationMaxAttempts = v; }

    public int getLockoutFailureThreshold() { return lockoutFailureThreshold; }
    public void setLockoutFailureThreshold(int v) { this.lockoutFailureThreshold = v; }

    public int getLockoutWindowMinutes() { return lockoutWindowMinutes; }
    public void setLockoutWindowMinutes(int v) { this.lockoutWindowMinutes = v; }

    public int getPasswordResetTtlMinutes() { return passwordResetTtlMinutes; }
    public void setPasswordResetTtlMinutes(int v) { this.passwordResetTtlMinutes = v; }
}

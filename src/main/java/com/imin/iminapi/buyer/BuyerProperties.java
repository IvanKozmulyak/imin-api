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
}

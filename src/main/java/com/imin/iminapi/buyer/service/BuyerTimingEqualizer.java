package com.imin.iminapi.buyer.service;

import com.imin.iminapi.security.PasswordHasher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The mechanism behind §2.2's "do not vary response time by branch".
 *
 * <p>That sentence is not self-executing (§15 D-1). BCrypt at cost 12
 * ({@code SecurityConfig:36-39}) takes 250–400 ms, and every credential flow
 * here has one branch that hashes and one that does not:
 *
 * <ul>
 *   <li><b>signup</b> — the create branch hashes the new password; the
 *       already-verified branch has nothing to hash.</li>
 *   <li><b>login</b> — a known account is checked with
 *       {@code BCryptPasswordEncoder.matches}; an unknown address, or a
 *       Google-only account with a NULL {@code password_hash}, would otherwise
 *       return in microseconds.</li>
 *   <li><b>{@code POST /buyer/emails}</b> (R1.3) — same shape.</li>
 * </ul>
 *
 * <p>Left alone, a 300 ms gap turns every "always 204, always identical" response
 * into a reliable oracle for "does this address have an imin account?" — which
 * is the exact question the neutral responses exist to refuse. So the negative
 * branch burns the same budget against a throwaway hash.
 *
 * <p>The dummy hash is generated once per JVM from a random UUID, so it cannot
 * collide with any real password and it costs one bcrypt at startup rather than
 * a constant baked into source.
 */
@Component
public class BuyerTimingEqualizer {

    private final PasswordHasher hasher;
    private final String dummyHash;

    public BuyerTimingEqualizer(PasswordHasher hasher) {
        this.hasher = hasher;
        this.dummyHash = hasher.hash(UUID.randomUUID().toString());
    }

    /**
     * Spend one bcrypt verification's worth of time. Always returns false in
     * effect — the result is deliberately discarded, because a match against a
     * random UUID is not a thing that can happen.
     */
    public void burnPasswordBudget(String submitted) {
        hasher.verify(submitted == null ? "" : submitted, dummyHash);
    }
}

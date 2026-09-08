package com.imin.iminapi.service.auth.verification;

import com.imin.iminapi.buyer.BuyerProperties;
import com.imin.iminapi.buyer.service.BuyerCodeHasher;
import com.imin.iminapi.model.EmailVerificationCode;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EmailVerificationCodeRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EmailVerificationServiceTest {

    EmailVerificationCodeRepository codes = mock(EmailVerificationCodeRepository.class);
    UserRepository users = mock(UserRepository.class);
    Clock clock = Clock.fixed(Instant.parse("2026-05-04T12:00:00Z"), ZoneOffset.UTC);
    BuyerCodeHasher hasher = newHasher();

    EmailVerificationService sut;

    /** Same pepper the buyer codes use; a fixed one so hashes are stable across the test. */
    private static BuyerCodeHasher newHasher() {
        BuyerProperties props = new BuyerProperties();
        props.setCodeSecret("test-pepper-for-organizer-codes");
        org.springframework.core.env.Environment env =
                mock(org.springframework.core.env.Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"test"});
        return new BuyerCodeHasher(props, env);
    }

    @BeforeEach
    void setUp() {
        sut = new EmailVerificationService(codes, users, hasher, clock, Duration.ofMinutes(10), 5);
        when(codes.save(any(EmailVerificationCode.class))).thenAnswer(inv -> {
            EmailVerificationCode c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private User newUser(String email) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setOrgId(UUID.randomUUID());
        u.setEmail(email);
        return u;
    }

    @Test
    void issueCode_invalidates_existing_active_and_returns_6_digit_code() {
        User u = newUser("ada@example.com");
        String code = sut.issueCode(u);

        assertThat(code).hasSize(6).matches("\\d{6}");
        verify(codes).invalidateActiveForUser(eq(u.getId()), any(Instant.class));
        verify(codes).save(any(EmailVerificationCode.class));
    }

    /**
     * The row must never carry the digits the buyer typed. A DB read is the whole
     * threat model here: four plaintext digits made every outstanding organizer
     * account takeable by anyone who could SELECT the table.
     */
    @Test
    void issueCode_never_persists_the_plaintext_digits() {
        User u = newUser("ada@example.com");
        String code = sut.issueCode(u);

        org.mockito.ArgumentCaptor<EmailVerificationCode> saved =
                org.mockito.ArgumentCaptor.forClass(EmailVerificationCode.class);
        verify(codes).save(saved.capture());
        assertThat(saved.getValue().getCode()).isNull();
    }

    @Test
    void verify_success_sets_verifiedAt_consumes_code_returns_user() {
        User u = newUser("ada@example.com");
        EmailVerificationCode active = activeCode(u, "123456", 5, 0);
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(u.getId()))
                .thenReturn(Optional.of(active));

        User result = sut.verify("ada@example.com", "123456");

        assertThat(result.getVerifiedAt()).isNotNull();
        assertThat(active.getConsumedAt()).isNotNull();
        verify(users).save(u);
    }

    @Test
    void verify_wrong_code_increments_attempts_and_throws_INVALID_CODE() {
        User u = newUser("ada@example.com");
        EmailVerificationCode active = activeCode(u, "123456", 5, 0);
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(u.getId()))
                .thenReturn(Optional.of(active));

        assertThatThrownBy(() -> sut.verify("ada@example.com", "000000"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_CODE);
        verify(codes).incrementAttempts(active.getId());
        assertThat(active.getConsumedAt()).isNull();
    }

    @Test
    void verify_after_max_attempts_throws_INVALID_CODE_without_incrementing() {
        User u = newUser("ada@example.com");
        EmailVerificationCode active = activeCode(u, "123456", 5, 5); // already at max
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(u.getId()))
                .thenReturn(Optional.of(active));

        assertThatThrownBy(() -> sut.verify("ada@example.com", "123456"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_CODE);
        assertThat(active.getAttempts()).isEqualTo(5);
    }

    @Test
    void verify_expired_code_throws_INVALID_CODE() {
        User u = newUser("ada@example.com");
        EmailVerificationCode active = activeCode(u, "123456", -1, 0); // expired 1 min ago
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(u.getId()))
                .thenReturn(Optional.of(active));

        assertThatThrownBy(() -> sut.verify("ada@example.com", "123456"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_CODE);
    }

    @Test
    void verify_with_no_pending_code_throws_INVALID_CODE() {
        User u = newUser("ada@example.com");
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(u.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.verify("ada@example.com", "123456"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_CODE);
    }

    @Test
    void verify_with_unknown_email_throws_INVALID_CODE() {
        when(users.findByEmailLower("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.verify("nobody@example.com", "123456"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_CODE);
    }

    /**
     * A legacy row written before V96 still verifies, so a rolling deploy does
     * not invalidate codes already sitting in inboxes. Those rows expire within
     * ten minutes and nothing writes the column any more.
     */
    @Test
    void verify_still_accepts_a_pre_V96_plaintext_row() {
        User u = newUser("ada@example.com");
        EmailVerificationCode legacy = new EmailVerificationCode();
        legacy.setId(UUID.randomUUID());
        legacy.setUserId(u.getId());
        legacy.setCode("4321");
        legacy.setExpiresAt(clock.instant().plus(Duration.ofMinutes(5)));
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(u.getId()))
                .thenReturn(Optional.of(legacy));

        assertThat(sut.verify("ada@example.com", "4321").getVerifiedAt()).isNotNull();
    }

    /**
     * The per-code attempt cap is not a brute-force control on its own: an
     * attacker asks for a fresh code every five guesses. The lockout counts
     * failures across every code the account has been issued in the window.
     */
    @Test
    void verify_locks_the_account_out_once_failures_across_codes_hit_the_threshold() {
        User u = newUser("ada@example.com");
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(codes.sumAttemptsSince(eq(u.getId()), any(Instant.class)))
                .thenReturn((long) EmailVerificationService.LOCKOUT_FAILURE_THRESHOLD);

        assertThatThrownBy(() -> sut.verify("ada@example.com", "123456"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.RATE_LIMITED);
        // Locked out BEFORE any code is read — a locked account is not an oracle.
        verify(codes, never()).findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(u.getId());
    }

    /** Below the threshold the flow is untouched. */
    @Test
    void verify_succeeds_while_below_the_lockout_threshold() {
        User u = newUser("ada@example.com");
        EmailVerificationCode active = activeCode(u, "123456", 5, 0);
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(codes.sumAttemptsSince(eq(u.getId()), any(Instant.class)))
                .thenReturn((long) EmailVerificationService.LOCKOUT_FAILURE_THRESHOLD - 1);
        when(codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(u.getId()))
                .thenReturn(Optional.of(active));

        assertThat(sut.verify("ada@example.com", "123456").getVerifiedAt()).isNotNull();
    }

    /**
     * An unknown address must not be able to tell a lockout from a bad code —
     * the lockout check runs only after the user resolves.
     */
    @Test
    void unknown_address_still_gets_the_neutral_INVALID_CODE() {
        when(users.findByEmailLower("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.verify("nobody@example.com", "123456"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_CODE);
        verify(codes, never()).sumAttemptsSince(any(), any());
    }

    private EmailVerificationCode activeCode(User u, String code, long minutesUntilExpiry, int attempts) {
        EmailVerificationCode c = new EmailVerificationCode();
        c.setId(UUID.randomUUID());
        c.setUserId(u.getId());
        c.setCodeHash(hasher.hash(code));
        c.setExpiresAt(clock.instant().plus(Duration.ofMinutes(minutesUntilExpiry)));
        c.setAttempts(attempts);
        return c;
    }
}

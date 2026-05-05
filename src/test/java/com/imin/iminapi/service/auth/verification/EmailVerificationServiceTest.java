package com.imin.iminapi.service.auth.verification;

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

    EmailVerificationService sut;

    @BeforeEach
    void setUp() {
        sut = new EmailVerificationService(codes, users, clock, Duration.ofMinutes(10), 5);
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
    void issueCode_invalidates_existing_active_and_returns_4_digit_code() {
        User u = newUser("ada@example.com");
        String code = sut.issueCode(u);

        assertThat(code).hasSize(4).matches("\\d{4}");
        verify(codes).invalidateActiveForUser(eq(u.getId()), any(Instant.class));
        verify(codes).save(any(EmailVerificationCode.class));
    }

    @Test
    void verify_success_sets_verifiedAt_consumes_code_returns_user() {
        User u = newUser("ada@example.com");
        EmailVerificationCode active = activeCode(u, "1234", 5, 0);
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(u.getId()))
                .thenReturn(Optional.of(active));

        User result = sut.verify("ada@example.com", "1234");

        assertThat(result.getVerifiedAt()).isNotNull();
        assertThat(active.getConsumedAt()).isNotNull();
        verify(users).save(u);
    }

    @Test
    void verify_wrong_code_increments_attempts_and_throws_INVALID_CODE() {
        User u = newUser("ada@example.com");
        EmailVerificationCode active = activeCode(u, "1234", 5, 0);
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(u.getId()))
                .thenReturn(Optional.of(active));

        assertThatThrownBy(() -> sut.verify("ada@example.com", "0000"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_CODE);
        verify(codes).incrementAttempts(active.getId());
        assertThat(active.getConsumedAt()).isNull();
    }

    @Test
    void verify_after_max_attempts_throws_INVALID_CODE_without_incrementing() {
        User u = newUser("ada@example.com");
        EmailVerificationCode active = activeCode(u, "1234", 5, 5); // already at max
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(u.getId()))
                .thenReturn(Optional.of(active));

        assertThatThrownBy(() -> sut.verify("ada@example.com", "1234"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_CODE);
        assertThat(active.getAttempts()).isEqualTo(5);
    }

    @Test
    void verify_expired_code_throws_INVALID_CODE() {
        User u = newUser("ada@example.com");
        EmailVerificationCode active = activeCode(u, "1234", -1, 0); // expired 1 min ago
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(u.getId()))
                .thenReturn(Optional.of(active));

        assertThatThrownBy(() -> sut.verify("ada@example.com", "1234"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_CODE);
    }

    @Test
    void verify_with_no_pending_code_throws_INVALID_CODE() {
        User u = newUser("ada@example.com");
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(u.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.verify("ada@example.com", "1234"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_CODE);
    }

    @Test
    void verify_with_unknown_email_throws_INVALID_CODE() {
        when(users.findByEmailLower("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.verify("nobody@example.com", "1234"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_CODE);
    }

    private EmailVerificationCode activeCode(User u, String code, long minutesUntilExpiry, int attempts) {
        EmailVerificationCode c = new EmailVerificationCode();
        c.setId(UUID.randomUUID());
        c.setUserId(u.getId());
        c.setCode(code);
        c.setExpiresAt(clock.instant().plus(Duration.ofMinutes(minutesUntilExpiry)));
        c.setAttempts(attempts);
        return c;
    }
}

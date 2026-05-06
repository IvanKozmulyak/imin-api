package com.imin.iminapi.service.auth;

import com.imin.iminapi.model.PasswordResetToken;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.PasswordResetTokenRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.PasswordHasher;
import com.imin.iminapi.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class PasswordResetServiceTest {

    PasswordResetTokenRepository tokens = mock(PasswordResetTokenRepository.class);
    UserRepository users = mock(UserRepository.class);
    TokenService tokenSvc = new TokenService();
    PasswordHasher hasher = new PasswordHasher(new BCryptPasswordEncoder(4));
    Clock clock = Clock.fixed(Instant.parse("2026-05-04T12:00:00Z"), ZoneOffset.UTC);

    PasswordResetService sut;

    @BeforeEach
    void setUp() {
        sut = new PasswordResetService(tokens, users, tokenSvc, hasher, clock, Duration.ofMinutes(30));
        when(tokens.save(any(PasswordResetToken.class))).thenAnswer(inv -> {
            PasswordResetToken t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private User newUser() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setOrgId(UUID.randomUUID());
        u.setEmail("ada@example.com");
        u.setPasswordHash(hasher.hash("oldpassword12"));
        return u;
    }

    @Test
    void issueToken_persists_hash_returns_cleartext() {
        User u = newUser();
        String token = sut.issueToken(u);

        assertThat(token).isNotBlank();
        verify(tokens).save(argThat(t ->
                t.getTokenHash().equals(tokenSvc.hashOf(token))
                && t.getUserId().equals(u.getId())));
    }

    @Test
    void consume_valid_token_updates_password_and_marks_consumed() {
        User u = newUser();
        String cleartext = "abc123";
        PasswordResetToken stored = active(u, cleartext, 10);
        when(tokens.findByTokenHash(tokenSvc.hashOf(cleartext))).thenReturn(Optional.of(stored));
        when(users.findById(u.getId())).thenReturn(Optional.of(u));

        User result = sut.consume(cleartext, "newpassword12");

        assertThat(result.getId()).isEqualTo(u.getId());
        assertThat(stored.getConsumedAt()).isNotNull();
        assertThat(hasher.verify("newpassword12", u.getPasswordHash())).isTrue();
    }

    @Test
    void consume_unknown_token_throws_INVALID_TOKEN() {
        when(tokens.findByTokenHash(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> sut.consume("garbage", "newpassword12"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_TOKEN);
    }

    @Test
    void consume_expired_token_throws_INVALID_TOKEN() {
        User u = newUser();
        String cleartext = "abc123";
        PasswordResetToken stored = active(u, cleartext, -1); // expired 1 min ago
        when(tokens.findByTokenHash(tokenSvc.hashOf(cleartext))).thenReturn(Optional.of(stored));
        assertThatThrownBy(() -> sut.consume(cleartext, "newpassword12"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_TOKEN);
    }

    @Test
    void consume_already_used_token_throws_INVALID_TOKEN() {
        User u = newUser();
        String cleartext = "abc123";
        PasswordResetToken stored = active(u, cleartext, 10);
        stored.setConsumedAt(clock.instant().minusSeconds(60));
        when(tokens.findByTokenHash(tokenSvc.hashOf(cleartext))).thenReturn(Optional.of(stored));
        assertThatThrownBy(() -> sut.consume(cleartext, "newpassword12"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_TOKEN);
    }

    private PasswordResetToken active(User u, String cleartext, long minutesUntilExpiry) {
        PasswordResetToken t = new PasswordResetToken();
        t.setId(UUID.randomUUID());
        t.setUserId(u.getId());
        t.setTokenHash(tokenSvc.hashOf(cleartext));
        t.setExpiresAt(clock.instant().plus(Duration.ofMinutes(minutesUntilExpiry)));
        return t;
    }
}

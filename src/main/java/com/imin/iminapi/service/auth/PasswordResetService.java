package com.imin.iminapi.service.auth;

import com.imin.iminapi.model.PasswordResetToken;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.PasswordResetTokenRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.PasswordHasher;
import com.imin.iminapi.security.TokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class PasswordResetService {

    public static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    public static final int EXPIRES_IN_MINUTES = (int) TOKEN_TTL.toMinutes();

    private final PasswordResetTokenRepository tokens;
    private final UserRepository users;
    private final TokenService tokenSvc;
    private final PasswordHasher hasher;
    private final Clock clock;
    private final Duration ttl;

    @Autowired
    public PasswordResetService(PasswordResetTokenRepository tokens,
                                 UserRepository users,
                                 TokenService tokenSvc,
                                 PasswordHasher hasher) {
        this(tokens, users, tokenSvc, hasher, Clock.systemUTC(), TOKEN_TTL);
    }

    /** Constructor used by tests. */
    public PasswordResetService(PasswordResetTokenRepository tokens,
                                 UserRepository users,
                                 TokenService tokenSvc,
                                 PasswordHasher hasher,
                                 Clock clock,
                                 Duration ttl) {
        this.tokens = tokens;
        this.users = users;
        this.tokenSvc = tokenSvc;
        this.hasher = hasher;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Transactional
    public String issueToken(User user) {
        Instant now = clock.instant();
        TokenService.IssuedToken issued = tokenSvc.issue();
        PasswordResetToken entity = new PasswordResetToken();
        entity.setUserId(user.getId());
        entity.setTokenHash(issued.tokenHash());
        entity.setExpiresAt(now.plus(ttl));
        tokens.save(entity);
        return issued.token();
    }

    @Transactional
    public User consume(String cleartext, String newPassword) {
        Instant now = clock.instant();
        String hash = tokenSvc.hashOf(cleartext);
        Optional<PasswordResetToken> maybe = tokens.findByTokenHash(hash);
        if (maybe.isEmpty()) throw invalidToken();
        PasswordResetToken token = maybe.get();
        if (token.getConsumedAt() != null) throw invalidToken();
        if (token.getExpiresAt().isBefore(now)) throw invalidToken();

        User user = users.findById(token.getUserId()).orElseThrow(this::invalidToken);
        user.setPasswordHash(hasher.hash(newPassword));
        users.save(user);

        token.setConsumedAt(now);
        tokens.save(token);
        return user;
    }

    private ApiException invalidToken() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_TOKEN,
                "Invalid or expired reset token");
    }
}

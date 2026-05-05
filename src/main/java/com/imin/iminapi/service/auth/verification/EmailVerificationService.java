package com.imin.iminapi.service.auth.verification;

import com.imin.iminapi.model.EmailVerificationCode;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EmailVerificationCodeRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

@Service
public class EmailVerificationService {

    public static final int MAX_ATTEMPTS = 5;
    public static final Duration CODE_TTL = Duration.ofMinutes(10);
    public static final int EXPIRES_IN_MINUTES = (int) CODE_TTL.toMinutes();

    private final EmailVerificationCodeRepository codes;
    private final UserRepository users;
    private final Clock clock;
    private final Duration ttl;
    private final int maxAttempts;
    private final SecureRandom rnd = new SecureRandom();

    public EmailVerificationService(EmailVerificationCodeRepository codes,
                                     UserRepository users) {
        this(codes, users, Clock.systemUTC(), CODE_TTL, MAX_ATTEMPTS);
    }

    /** Constructor used by tests for clock + parameter overrides. */
    public EmailVerificationService(EmailVerificationCodeRepository codes,
                                     UserRepository users,
                                     Clock clock,
                                     Duration ttl,
                                     int maxAttempts) {
        this.codes = codes;
        this.users = users;
        this.clock = clock;
        this.ttl = ttl;
        this.maxAttempts = maxAttempts;
    }

    @Transactional
    public String issueCode(User user) {
        codes.invalidateActiveForUser(user.getId(), clock.instant());
        String code = String.format(Locale.ROOT, "%04d", rnd.nextInt(10_000));
        EmailVerificationCode entity = new EmailVerificationCode();
        entity.setUserId(user.getId());
        entity.setCode(code);
        entity.setExpiresAt(clock.instant().plus(ttl));
        codes.save(entity);
        return code;
    }

    @Transactional
    public User verify(String email, String code) {
        Optional<User> maybeUser = users.findByEmailLower(email.toLowerCase());
        if (maybeUser.isEmpty()) {
            throw invalidCode();
        }
        User user = maybeUser.get();
        Optional<EmailVerificationCode> maybeActive =
                codes.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(user.getId());
        if (maybeActive.isEmpty()) {
            throw invalidCode();
        }
        EmailVerificationCode active = maybeActive.get();

        if (active.getAttempts() >= maxAttempts) throw invalidCode();
        if (active.getExpiresAt().isBefore(clock.instant())) throw invalidCode();

        if (!active.getCode().equals(code)) {
            active.setAttempts(active.getAttempts() + 1);
            codes.save(active);
            throw invalidCode();
        }

        active.setConsumedAt(clock.instant());
        codes.save(active);
        user.setVerifiedAt(clock.instant());
        users.save(user);
        return user;
    }

    private ApiException invalidCode() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_CODE,
                "Invalid or expired verification code");
    }
}

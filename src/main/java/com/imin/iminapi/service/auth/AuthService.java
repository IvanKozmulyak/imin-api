package com.imin.iminapi.service.auth;

import com.imin.iminapi.dto.OrganizationDto;
import com.imin.iminapi.dto.UserDto;
import com.imin.iminapi.dto.auth.AuthResponse;
import com.imin.iminapi.dto.auth.LoginRequest;
import com.imin.iminapi.dto.auth.MeResponse;
import com.imin.iminapi.dto.auth.SignupRequest;
import com.imin.iminapi.model.AuthSession;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AuthSessionRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.PasswordHasher;
import com.imin.iminapi.security.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class AuthService {

    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final AuthSessionRepository sessions;
    private final PasswordHasher hasher;
    private final TokenService tokens;
    private final Duration sessionTtl;

    public AuthService(OrganizationRepository orgs,
                       UserRepository users,
                       AuthSessionRepository sessions,
                       PasswordHasher hasher,
                       TokenService tokens,
                       @Value("${imin.auth.session-ttl-days}") long sessionTtlDays) {
        this(orgs, users, sessions, hasher, tokens, Duration.ofDays(sessionTtlDays));
    }

    /** Constructor used by tests. */
    public AuthService(OrganizationRepository orgs,
                       UserRepository users,
                       AuthSessionRepository sessions,
                       PasswordHasher hasher,
                       TokenService tokens,
                       Duration sessionTtl) {
        this.orgs = orgs;
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
        this.tokens = tokens;
        this.sessionTtl = sessionTtl;
    }

    @Transactional
    public AuthResponse signup(SignupRequest req) {
        String emailLower = req.email().toLowerCase();
        if (users.existsByEmailLower(emailLower)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE,
                    "Email already registered", java.util.Map.of("email", "already registered"));
        }
        Organization org = new Organization();
        org.setName(req.orgName());
        org.setContactEmail(req.email());
        org.setCountry(req.country().toUpperCase());
        org.setTimezone("UTC");
        Organization savedOrg = orgs.save(org);

        User user = new User();
        user.setOrgId(savedOrg.getId());
        user.setEmail(req.email());
        user.setName("");
        user.setPasswordHash(hasher.hash(req.password()));
        user.setRole(UserRole.OWNER);
        user.setAvatarInitials(deriveInitials(req.email()));
        User savedUser = users.save(user);

        String token = issueSession(savedUser);
        return new AuthResponse(token, UserDto.from(savedUser), OrganizationDto.from(savedOrg));
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        Optional<User> maybe = users.findByEmailLower(req.email().toLowerCase());
        if (maybe.isEmpty() || maybe.get().getPasswordHash() == null
                || !hasher.verify(req.password(), maybe.get().getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid credentials");
        }
        User user = maybe.get();
        Organization org = orgs.findById(user.getOrgId())
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL, "Org missing"));
        user.setLastActiveAt(Instant.now());
        users.save(user);
        String token = issueSession(user);
        return new AuthResponse(token, UserDto.from(user), OrganizationDto.from(org));
    }

    @Transactional
    public void logout(AuthPrincipal principal) {
        sessions.findById(principal.sessionId()).ifPresent(s -> {
            s.setRevokedAt(Instant.now());
            sessions.save(s);
        });
    }

    @Transactional(readOnly = true)
    public MeResponse me(AuthPrincipal principal) {
        User user = users.findById(principal.userId())
                .orElseThrow(() -> ApiException.notFound("User"));
        Organization org = orgs.findById(user.getOrgId())
                .orElseThrow(() -> ApiException.notFound("Organization"));
        return new MeResponse(UserDto.from(user), OrganizationDto.from(org));
    }

    private String issueSession(User user) {
        TokenService.IssuedToken issued = tokens.issue();
        AuthSession s = new AuthSession();
        s.setUserId(user.getId());
        s.setTokenHash(issued.tokenHash());
        s.setExpiresAt(Instant.now().plus(sessionTtl));
        sessions.save(s);
        return issued.token();
    }

    private static String deriveInitials(String emailOrName) {
        String src = emailOrName == null ? "" : emailOrName;
        int at = src.indexOf('@');
        if (at >= 0) src = src.substring(0, at);
        if (src.isBlank()) return "";
        if (src.length() == 1) return src.substring(0, 1).toUpperCase();
        return src.substring(0, 2).toUpperCase();
    }
}

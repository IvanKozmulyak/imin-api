package com.imin.iminapi.service.auth;

import com.imin.iminapi.dto.OrganizationDto;
import com.imin.iminapi.dto.UserDto;
import com.imin.iminapi.dto.auth.AuthResponse;
import com.imin.iminapi.dto.auth.LoginRequest;
import com.imin.iminapi.dto.auth.MeResponse;
import com.imin.iminapi.dto.auth.SignupRequest;
import com.imin.iminapi.dto.auth.VerificationPendingResponse;
import com.imin.iminapi.email.AccountEmailService;
import com.imin.iminapi.email.EmailProperties;
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
import com.imin.iminapi.service.auth.verification.EmailVerificationService;
import com.imin.iminapi.util.Slugger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final AuthSessionRepository sessions;
    private final PasswordHasher hasher;
    private final TokenService tokens;
    private final EmailVerificationService verificationSvc;
    private final PasswordResetService passwordResetSvc;
    private final AccountEmailService accountEmail;
    private final EmailProperties props;
    private final Duration sessionTtl;

    @Autowired
    public AuthService(OrganizationRepository orgs,
                       UserRepository users,
                       AuthSessionRepository sessions,
                       PasswordHasher hasher,
                       TokenService tokens,
                       EmailVerificationService verificationSvc,
                       PasswordResetService passwordResetSvc,
                       AccountEmailService accountEmail,
                       EmailProperties emailProperties,
                       @Value("${imin.auth.session-ttl-days}") long sessionTtlDays) {
        this(orgs, users, sessions, hasher, tokens, verificationSvc, passwordResetSvc, accountEmail,
                emailProperties, Duration.ofDays(sessionTtlDays));
    }

    /** Constructor used by tests. */
    public AuthService(OrganizationRepository orgs,
                       UserRepository users,
                       AuthSessionRepository sessions,
                       PasswordHasher hasher,
                       TokenService tokens,
                       EmailVerificationService verificationSvc,
                       PasswordResetService passwordResetSvc,
                       AccountEmailService accountEmail,
                       EmailProperties emailProperties,
                       Duration sessionTtl) {
        this.orgs = orgs;
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
        this.tokens = tokens;
        this.verificationSvc = verificationSvc;
        this.passwordResetSvc = passwordResetSvc;
        this.accountEmail = accountEmail;
        this.props = emailProperties;
        this.sessionTtl = sessionTtl;
    }

    @Transactional
    public VerificationPendingResponse signup(SignupRequest req) {
        com.imin.iminapi.util.SanctionedCountries.requireAllowed(req.country());
        com.imin.iminapi.util.StripeSupportedCountries.requireSupported(req.country());
        String emailLower = req.email().toLowerCase();
        if (users.existsByEmailLower(emailLower)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE,
                    "Email already registered", java.util.Map.of("email", "already registered"));
        }
        Organization org = new Organization();
        org.setName(req.orgName());
        org.setSlug(uniqueSlug(req.orgName()));
        org.setContactEmail(req.email());
        org.setCountry(req.country().toUpperCase());
        org.setTimezone("UTC");
        Organization savedOrg;
        try {
            // saveAndFlush so the unique-slug constraint fires here, not at txn commit.
            savedOrg = orgs.saveAndFlush(org);
        } catch (DataIntegrityViolationException e) {
            // Rare race: another concurrent signup claimed our slug between
            // existsBySlug() and save(). Surface as a 409 instead of a 500;
            // the client may retry signup (a second uniqueSlug pass will pick
            // a different suffix). See spec "Risks / open questions".
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE,
                    "Organization slug conflict, please retry signup");
        }

        String firstName = req.firstName().trim();
        String lastName = req.lastName().trim();
        User user = new User();
        user.setOrgId(savedOrg.getId());
        user.setEmail(req.email());
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setPasswordHash(hasher.hash(req.password()));
        user.setRole(UserRole.OWNER);
        user.setAvatarInitials(deriveInitials(firstName, lastName));
        // verifiedAt left null until /verify-email succeeds
        User savedUser = users.save(user);

        String code = verificationSvc.issueCode(savedUser);
        // Sync, propagate failure: signup must fail loudly if the user can't receive the code.
        accountEmail.sendVerificationCode(savedUser, code, EmailVerificationService.EXPIRES_IN_MINUTES);

        return VerificationPendingResponse.forEmail(savedUser.getEmail());
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        Optional<User> maybe = users.findByEmailLower(req.email().toLowerCase());
        if (maybe.isEmpty() || maybe.get().getPasswordHash() == null
                || !hasher.verify(req.password(), maybe.get().getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid credentials");
        }
        User user = maybe.get();
        if (user.getVerifiedAt() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.EMAIL_NOT_VERIFIED, "Email not verified");
        }
        Organization org = orgs.findById(user.getOrgId())
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL, "Org missing"));
        // Defensive: an account created before sanctions were enforced (or via direct DB write)
        // shouldn't be able to log back in if the country has since landed on the list.
        com.imin.iminapi.util.SanctionedCountries.requireAllowed(org.getCountry());
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

    @Transactional
    public AuthResponse verifyEmail(com.imin.iminapi.dto.auth.VerifyEmailRequest req) {
        User user = verificationSvc.verify(req.email(), req.code());
        Organization org = orgs.findById(user.getOrgId())
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL, "Org missing"));
        user.setLastActiveAt(Instant.now());
        users.save(user);
        String token = issueSession(user);
        // Welcome email is non-critical — swallow failures so a Resend outage doesn't block verification.
        try {
            accountEmail.sendWelcome(user);
        } catch (RuntimeException e) {
            log.warn("Welcome email send failed for {}: {}", user.getEmail(), e.getMessage());
        }
        return new AuthResponse(token, UserDto.from(user), OrganizationDto.from(org));
    }

    @Transactional
    public void resendVerification(com.imin.iminapi.dto.auth.ResendVerificationRequest req) {
        Optional<User> maybe = users.findByEmailLower(req.email().toLowerCase());
        if (maybe.isEmpty()) return;                                  // anti-enumeration
        User user = maybe.get();
        if (user.getVerifiedAt() != null) return;                     // already verified
        String code = verificationSvc.issueCode(user);
        // Sync, propagate failure: user explicitly asked for a code.
        accountEmail.sendVerificationCode(user, code, EmailVerificationService.EXPIRES_IN_MINUTES);
    }

    @Transactional
    public void forgotPassword(com.imin.iminapi.dto.auth.ForgotPasswordRequest req) {
        Optional<User> maybe = users.findByEmailLower(req.email().toLowerCase());
        if (maybe.isEmpty()) return;                                  // anti-enumeration
        User user = maybe.get();
        String token = passwordResetSvc.issueToken(user);
        String resetUrl = props.getAppBaseUrl() + "/reset-password?token=" + token;
        // Sync, swallow + log: anti-enumeration trumps loud-fail; we cannot signal failure to the caller.
        try {
            accountEmail.sendPasswordReset(user, resetUrl, PasswordResetService.EXPIRES_IN_MINUTES);
        } catch (RuntimeException e) {
            log.error("Password-reset email send failed for {}: {}", user.getEmail(), e.getMessage(), e);
        }
    }

    @Transactional
    public void resetPassword(com.imin.iminapi.dto.auth.ResetPasswordRequest req) {
        User user = passwordResetSvc.consume(req.token(), req.newPassword());
        sessions.revokeAllForUser(user.getId(), Instant.now());
        try {
            accountEmail.sendPasswordChangedNotification(user);
        } catch (RuntimeException e) {
            log.warn("Password-changed notification failed for {}: {}", user.getEmail(), e.getMessage());
        }
    }

    /**
     * Authenticated password change. Verifies the current password, rotates the hash,
     * revokes every session (including this one), then issues a fresh session so the
     * calling browser stays logged in with the returned token. Wrong current password
     * is 400 (not 401 — the FE treats 401 as session expiry and force-logs-out).
     */
    @Transactional
    public AuthResponse changePassword(AuthPrincipal principal, com.imin.iminapi.dto.auth.ChangePasswordRequest req) {
        User user = users.findById(principal.userId())
                .orElseThrow(() -> ApiException.notFound("User"));
        if (user.getPasswordHash() == null || !hasher.verify(req.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.AUTH_INVALID_CREDENTIALS,
                    "Current password is incorrect");
        }
        user.setPasswordHash(hasher.hash(req.newPassword()));
        users.save(user);
        sessions.revokeAllForUser(user.getId(), Instant.now());
        String token = issueSession(user);
        try {
            accountEmail.sendPasswordChangedNotification(user);
        } catch (RuntimeException e) {
            log.warn("Password-changed notification failed for {}: {}", user.getEmail(), e.getMessage());
        }
        Organization org = orgs.findById(user.getOrgId())
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL, "Org missing"));
        return new AuthResponse(token, UserDto.from(user), OrganizationDto.from(org));
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

    private String uniqueSlug(String orgName) {
        String base = Slugger.slugify(orgName);
        if (!orgs.existsBySlug(base)) {
            return base;
        }
        for (int n = 2; n <= 10; n++) {
            String candidate = base + "-" + n;
            if (!orgs.existsBySlug(candidate)) {
                return candidate;
            }
        }
        // Fallback: append random 6-hex chars
        byte[] bytes = new byte[3];
        RANDOM.nextBytes(bytes);
        return base + "-" + HexFormat.of().formatHex(bytes);
    }

    /** Two-letter initials from first + last name. Falls back gracefully if either is blank. */
    private static String deriveInitials(String firstName, String lastName) {
        StringBuilder sb = new StringBuilder(2);
        appendInitial(sb, firstName);
        appendInitial(sb, lastName);
        return sb.toString().toUpperCase();
    }

    private static void appendInitial(StringBuilder sb, String s) {
        if (sb.length() >= 2 || s == null) return;
        String t = s.trim();
        if (!t.isEmpty()) sb.append(t.charAt(0));
    }
}

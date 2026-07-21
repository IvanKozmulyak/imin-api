package com.imin.iminapi.oauth;

import com.imin.iminapi.dto.OrganizationDto;
import com.imin.iminapi.dto.UserDto;
import com.imin.iminapi.dto.auth.AuthResponse;
import com.imin.iminapi.model.AuthSession;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserIdentity;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AuthSessionRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserIdentityRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.TokenService;
import com.imin.iminapi.util.Slugger;
import com.imin.iminapi.util.Times;
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

/**
 * Resolves a verified {@link OAuthUserInfo} into a logged-in imin session,
 * creating or linking accounts as needed. This is the provider-agnostic heart of
 * social sign-in and the main unit under test — it takes an already-verified
 * {@code OAuthUserInfo} (no Google/Apple round-trip) and always ends by minting a
 * normal USER {@link AuthSession}, identical to password login.
 *
 * <p>Resolution matrix (in order):
 * <ol>
 *   <li><b>Known identity</b> — {@code (provider, sub)} already linked → log that
 *       user straight in.</li>
 *   <li><b>No email</b> — reject 400 {@code OAUTH_EMAIL_REQUIRED} (users.email is
 *       NOT NULL; Google/Apple with the email scope always supply one).</li>
 *   <li><b>Existing email + verified</b> — link a new identity to that user (and
 *       backfill {@code verified_at} if unset), then log in.</li>
 *   <li><b>Existing email + unverified</b> — reject 409 {@code OAUTH_EMAIL_CONFLICT}.
 *       Auto-linking an unverified email to a password account is an
 *       account-takeover vector.</li>
 *   <li><b>New email</b> — auto-provision an Organization + OWNER user (no
 *       password, {@code verified_at = now}) and link the identity.</li>
 * </ol>
 */
@Service
public class OAuthAccountService {

    private static final Logger log = LoggerFactory.getLogger(OAuthAccountService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final AuthSessionRepository sessions;
    private final UserIdentityRepository identities;
    private final TokenService tokens;
    private final Duration sessionTtl;
    private final String defaultCountry;

    @Autowired
    public OAuthAccountService(OrganizationRepository orgs,
                               UserRepository users,
                               AuthSessionRepository sessions,
                               UserIdentityRepository identities,
                               TokenService tokens,
                               OAuthProperties props,
                               @Value("${imin.auth.session-ttl-days}") long sessionTtlDays) {
        this(orgs, users, sessions, identities, tokens,
                Duration.ofDays(sessionTtlDays), props.getDefaultCountry());
    }

    /** Constructor used by tests. */
    public OAuthAccountService(OrganizationRepository orgs,
                               UserRepository users,
                               AuthSessionRepository sessions,
                               UserIdentityRepository identities,
                               TokenService tokens,
                               Duration sessionTtl,
                               String defaultCountry) {
        this.orgs = orgs;
        this.users = users;
        this.sessions = sessions;
        this.identities = identities;
        this.tokens = tokens;
        this.sessionTtl = sessionTtl;
        this.defaultCountry = defaultCountry;
    }

    @Transactional
    public AuthResponse resolve(OAuthUserInfo info) {
        // (1) Known identity — straight in.
        Optional<UserIdentity> known = identities.findByProviderAndProviderUserId(info.provider(), info.subject());
        if (known.isPresent()) {
            User user = users.findById(known.get().getUserId())
                    .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL,
                            "Linked identity points at a missing user"));
            return loginExisting(user);
        }

        // (2) Email is mandatory (users.email is NOT NULL).
        String email = info.email() == null ? null : info.email().trim();
        if (email == null || email.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.OAUTH_EMAIL_REQUIRED,
                    "Email permission is required to sign in");
        }

        Optional<User> byEmail = users.findByEmailLower(email.toLowerCase());
        if (byEmail.isPresent()) {
            User user = byEmail.get();
            // (4) Existing email but the provider did not verify it — do NOT link.
            if (!info.emailVerified()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.OAUTH_EMAIL_CONFLICT,
                        "An account with this email already exists. Sign in with your password.");
            }
            // (3) Verified email — link the new identity to the existing account.
            linkIdentity(user, info);
            if (user.getVerifiedAt() == null) {
                user.setVerifiedAt(Times.nowMicros());
            }
            log.info("OAuth link: provider={} linked to existing user {}", info.provider(), user.getId());
            return loginExisting(user);
        }

        // (5) Brand-new email — auto-provision org + owner, then link.
        User created = provision(info, email);
        linkIdentity(created, info);
        log.info("OAuth signup: provider={} provisioned new user {} / org {}",
                info.provider(), created.getId(), created.getOrgId());
        return finish(created);
    }

    // ----- helpers -----

    private AuthResponse loginExisting(User user) {
        user.setLastActiveAt(Instant.now());
        users.save(user);
        return finish(user);
    }

    private AuthResponse finish(User user) {
        Organization org = orgs.findById(user.getOrgId())
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL, "Org missing"));
        String token = issueSession(user);
        return new AuthResponse(token, UserDto.from(user), OrganizationDto.from(org));
    }

    private void linkIdentity(User user, OAuthUserInfo info) {
        UserIdentity identity = new UserIdentity();
        identity.setUserId(user.getId());
        identity.setProvider(info.provider());
        identity.setProviderUserId(info.subject());
        identity.setEmail(info.email());
        identity.setDisplayName(info.displayName());
        identities.save(identity);
    }

    /**
     * Auto-provision an Organization + OWNER user for a first-time social sign-in.
     * Mirrors {@code AuthService.signup}'s org/user creation (unique slug, derived
     * initials) minus the password + email-verification steps — the account has no
     * password and is marked verified because the provider authenticated it. No
     * country is collected in the social flow, so the org takes the configured
     * default; the organizer can change it in settings.
     */
    private User provision(OAuthUserInfo info, String email) {
        String first = safe(info.firstName());
        String last = safe(info.lastName());
        String orgName = deriveOrgName(first);

        Organization org = new Organization();
        org.setName(orgName);
        org.setSlug(uniqueSlug(orgName));
        org.setContactEmail(email);
        org.setCountry(defaultCountry);
        org.setTimezone("UTC");
        Organization savedOrg;
        try {
            savedOrg = orgs.saveAndFlush(org);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE,
                    "Organization slug conflict, please retry sign-in");
        }

        User user = new User();
        user.setOrgId(savedOrg.getId());
        user.setEmail(email);
        user.setFirstName(first);
        user.setLastName(last);
        user.setRole(UserRole.OWNER);
        user.setAvatarInitials(deriveInitials(first, last));
        user.setVerifiedAt(Times.nowMicros()); // provider-authenticated — no email code loop
        user.setLastActiveAt(Instant.now());
        // passwordHash left null — this account signs in via the provider only
        return users.save(user);
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
        byte[] bytes = new byte[3];
        RANDOM.nextBytes(bytes);
        return base + "-" + HexFormat.of().formatHex(bytes);
    }

    /** "Ada's events" from a first name, or "My events" when the provider gave none. */
    private static String deriveOrgName(String firstName) {
        String f = safe(firstName);
        return f.isEmpty() ? "My events" : f + "'s events";
    }

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

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}

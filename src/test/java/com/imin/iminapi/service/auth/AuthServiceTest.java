package com.imin.iminapi.service.auth;

import com.imin.iminapi.dto.auth.AuthResponse;
import com.imin.iminapi.dto.auth.SignupRequest;
import com.imin.iminapi.model.AuthSession;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AuthSessionRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.PasswordHasher;
import com.imin.iminapi.security.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    OrganizationRepository orgs = mock(OrganizationRepository.class);
    UserRepository users = mock(UserRepository.class);
    AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    PasswordHasher hasher = new PasswordHasher(new BCryptPasswordEncoder(4));
    TokenService tokens = new TokenService();
    com.imin.iminapi.service.auth.verification.EmailVerificationService verificationSvc =
            mock(com.imin.iminapi.service.auth.verification.EmailVerificationService.class);
    com.imin.iminapi.service.auth.PasswordResetService passwordResetSvc =
            mock(com.imin.iminapi.service.auth.PasswordResetService.class);
    com.imin.iminapi.email.AccountEmailService accountEmail =
            mock(com.imin.iminapi.email.AccountEmailService.class);

    com.imin.iminapi.email.EmailProperties emailProps = makeEmailProps();

    AuthService sut = new AuthService(orgs, users, sessions, hasher, tokens,
            verificationSvc, passwordResetSvc, accountEmail,
            emailProps, Duration.ofDays(30));

    @Test
    void signup_creates_org_and_owner_issues_code_sends_email_returns_pending() {
        when(users.existsByEmailLower("ada@example.com")).thenReturn(false);
        when(orgs.existsBySlug(any())).thenReturn(false);
        when(orgs.saveAndFlush(any(Organization.class))).thenAnswer(inv -> {
            Organization o = inv.getArgument(0); o.setId(java.util.UUID.randomUUID()); return o;
        });
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0); u.setId(java.util.UUID.randomUUID()); return u;
        });
        when(verificationSvc.issueCode(any(User.class))).thenReturn("1234");

        com.imin.iminapi.dto.auth.VerificationPendingResponse r =
                sut.signup(new SignupRequest("ada@example.com", "lovelace12", "Ada", "Lovelace", "Ada Co", "GB"));

        assertThat(r.message()).isEqualTo("Verification email sent");
        assertThat(r.email()).isEqualTo("ada@example.com");
        verify(verificationSvc).issueCode(any(User.class));
        verify(accountEmail).sendVerificationCode(any(User.class), eq("1234"),
                eq(com.imin.iminapi.service.auth.verification.EmailVerificationService.EXPIRES_IN_MINUTES));
        verify(sessions, never()).save(any(AuthSession.class));
    }

    @Test
    void signup_propagates_when_verification_email_send_fails() {
        when(users.existsByEmailLower("ada@example.com")).thenReturn(false);
        when(orgs.saveAndFlush(any(Organization.class))).thenAnswer(inv -> {
            Organization o = inv.getArgument(0); o.setId(java.util.UUID.randomUUID()); return o;
        });
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0); u.setId(java.util.UUID.randomUUID()); return u;
        });
        when(verificationSvc.issueCode(any(User.class))).thenReturn("1234");
        org.mockito.Mockito.doThrow(new ApiException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                com.imin.iminapi.security.ErrorCode.UPSTREAM_UNAVAILABLE, "down"))
            .when(accountEmail).sendVerificationCode(any(User.class), any(), anyInt());

        assertThatThrownBy(() -> sut.signup(new SignupRequest("ada@example.com", "lovelace12", "Ada", "Lovelace", "Ada Co", "GB")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    void signup_blocked_for_sanctioned_country() {
        assertThatThrownBy(() -> sut.signup(new SignupRequest("ada@example.com", "lovelace12",
                "Ada", "Lovelace", "X", "IR")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.COUNTRY_NOT_ALLOWED);
        verifyNoInteractions(orgs);
        verify(users, never()).save(any(User.class));
    }

    @Test
    void signup_with_existing_email_throws_DUPLICATE() {
        when(users.existsByEmailLower("dupe@example.com")).thenReturn(true);
        assertThatThrownBy(() -> sut.signup(new SignupRequest("dupe@example.com", "valid12345", "Dupe", "User", "X", "FR")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.DUPLICATE);
    }

    @Test
    void avatar_initials_are_derived_from_first_and_last_name() {
        when(users.existsByEmailLower("ada@example.com")).thenReturn(false);
        when(orgs.saveAndFlush(any(Organization.class))).thenAnswer(inv -> {
            Organization o = inv.getArgument(0); o.setId(java.util.UUID.randomUUID()); return o;
        });
        java.util.concurrent.atomic.AtomicReference<User> savedUser = new java.util.concurrent.atomic.AtomicReference<>();
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0); u.setId(java.util.UUID.randomUUID());
            savedUser.set(u); return u;
        });
        when(verificationSvc.issueCode(any(User.class))).thenReturn("0001");

        sut.signup(new SignupRequest("ada@example.com", "lovelace12", "Ada", "Lovelace", "X", "GB"));

        assertThat(savedUser.get().getAvatarInitials()).isEqualTo("AL");
        assertThat(savedUser.get().getFirstName()).isEqualTo("Ada");
        assertThat(savedUser.get().getLastName()).isEqualTo("Lovelace");
    }

    @Test
    void login_with_valid_password_returns_token() {
        User stored = new User();
        stored.setId(java.util.UUID.randomUUID());
        stored.setOrgId(java.util.UUID.randomUUID());
        stored.setEmail("ada@example.com");
        stored.setPasswordHash(hasher.hash("lovelace12"));
        stored.setRole(UserRole.OWNER);
        stored.setVerifiedAt(java.time.Instant.now());
        when(users.findByEmailLower("ada@example.com")).thenReturn(java.util.Optional.of(stored));

        Organization org = new Organization();
        org.setId(stored.getOrgId());
        org.setName("Ada Co");
        org.setContactEmail("ada@example.com");
        org.setCountry("GB");
        when(orgs.findById(stored.getOrgId())).thenReturn(java.util.Optional.of(org));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sessions.save(any(AuthSession.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthResponse r = sut.login(new com.imin.iminapi.dto.auth.LoginRequest("ada@example.com", "lovelace12"));
        assertThat(r.token()).isNotBlank();
        assertThat(r.user().id()).isEqualTo(stored.getId());
    }

    @Test
    void login_blocked_when_org_country_now_sanctioned() {
        User stored = new User();
        stored.setId(java.util.UUID.randomUUID());
        stored.setOrgId(java.util.UUID.randomUUID());
        stored.setEmail("ada@example.com");
        stored.setPasswordHash(hasher.hash("lovelace12"));
        stored.setRole(UserRole.OWNER);
        stored.setVerifiedAt(java.time.Instant.now());
        when(users.findByEmailLower("ada@example.com")).thenReturn(java.util.Optional.of(stored));

        Organization org = new Organization();
        org.setId(stored.getOrgId());
        org.setCountry("IR"); // sanctioned
        when(orgs.findById(stored.getOrgId())).thenReturn(java.util.Optional.of(org));

        assertThatThrownBy(() -> sut.login(new com.imin.iminapi.dto.auth.LoginRequest("ada@example.com", "lovelace12")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.COUNTRY_NOT_ALLOWED);
        verify(sessions, never()).save(any(AuthSession.class));
    }

    @Test
    void login_with_wrong_password_throws_AUTH_INVALID_CREDENTIALS() {
        User stored = new User();
        stored.setId(java.util.UUID.randomUUID());
        stored.setOrgId(java.util.UUID.randomUUID());
        stored.setEmail("ada@example.com");
        stored.setPasswordHash(hasher.hash("lovelace12"));
        stored.setRole(UserRole.OWNER);
        when(users.findByEmailLower("ada@example.com")).thenReturn(java.util.Optional.of(stored));

        assertThatThrownBy(() -> sut.login(new com.imin.iminapi.dto.auth.LoginRequest("ada@example.com", "WRONG")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    void login_with_unknown_email_also_throws_AUTH_INVALID_CREDENTIALS() {
        when(users.findByEmailLower(any())).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> sut.login(new com.imin.iminapi.dto.auth.LoginRequest("nobody@example.com", "anything12345")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    void logout_revokes_the_current_session() {
        java.util.UUID sid = java.util.UUID.randomUUID();
        AuthSession s = new AuthSession();
        s.setId(sid);
        s.setUserId(java.util.UUID.randomUUID());
        s.setExpiresAt(Instant.now().plus(Duration.ofDays(30)));
        when(sessions.findById(sid)).thenReturn(java.util.Optional.of(s));

        sut.logout(new com.imin.iminapi.security.AuthPrincipal(s.getUserId(), java.util.UUID.randomUUID(), UserRole.OWNER, sid));
        assertThat(s.getRevokedAt()).isNotNull();
        verify(sessions).save(s);
    }

    @Test
    void me_returns_user_and_org() {
        java.util.UUID userId = java.util.UUID.randomUUID();
        java.util.UUID orgId = java.util.UUID.randomUUID();
        User u = new User(); u.setId(userId); u.setOrgId(orgId); u.setEmail("ada@example.com"); u.setRole(UserRole.OWNER);
        Organization o = new Organization(); o.setId(orgId); o.setName("Ada Co"); o.setContactEmail("a@b.c"); o.setCountry("GB");
        when(users.findById(userId)).thenReturn(java.util.Optional.of(u));
        when(orgs.findById(orgId)).thenReturn(java.util.Optional.of(o));

        var r = sut.me(new com.imin.iminapi.security.AuthPrincipal(userId, orgId, UserRole.OWNER, java.util.UUID.randomUUID()));
        assertThat(r.user().id()).isEqualTo(userId);
        assertThat(r.org().id()).isEqualTo(orgId);
    }

    @Test
    void login_with_unverified_user_throws_EMAIL_NOT_VERIFIED() {
        User stored = new User();
        stored.setId(java.util.UUID.randomUUID());
        stored.setOrgId(java.util.UUID.randomUUID());
        stored.setEmail("ada@example.com");
        stored.setPasswordHash(hasher.hash("lovelace12"));
        stored.setRole(UserRole.OWNER);
        stored.setVerifiedAt(null); // <-- unverified
        when(users.findByEmailLower("ada@example.com")).thenReturn(java.util.Optional.of(stored));

        assertThatThrownBy(() -> sut.login(new com.imin.iminapi.dto.auth.LoginRequest("ada@example.com", "lovelace12")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.EMAIL_NOT_VERIFIED);
    }

    // ----- verifyEmail -----

    @Test
    void verifyEmail_marks_user_verified_issues_session_sends_welcome() {
        User u = new User();
        u.setId(java.util.UUID.randomUUID());
        u.setOrgId(java.util.UUID.randomUUID());
        u.setEmail("ada@example.com");
        u.setRole(UserRole.OWNER);
        u.setVerifiedAt(null); // <-- starts unverified

        Organization org = new Organization();
        org.setId(u.getOrgId());
        org.setName("Ada Co");
        org.setContactEmail("ada@example.com");
        org.setCountry("GB");

        when(verificationSvc.verify("ada@example.com", "1234")).thenAnswer(inv -> {
            u.setVerifiedAt(java.time.Instant.now()); // mirror real contract
            return u;
        });
        when(orgs.findById(u.getOrgId())).thenReturn(java.util.Optional.of(org));
        when(sessions.save(any(AuthSession.class))).thenAnswer(inv -> inv.getArgument(0));

        com.imin.iminapi.dto.auth.AuthResponse r = sut.verifyEmail(
                new com.imin.iminapi.dto.auth.VerifyEmailRequest("ada@example.com", "1234"));

        assertThat(r.token()).isNotBlank();
        assertThat(r.user().email()).isEqualTo("ada@example.com");
        assertThat(u.getVerifiedAt()).isNotNull(); // mock did set it
        verify(accountEmail).sendWelcome(u);
    }

    @Test
    void verifyEmail_swallows_welcome_email_failure() {
        User u = new User();
        u.setId(java.util.UUID.randomUUID());
        u.setOrgId(java.util.UUID.randomUUID());
        u.setEmail("ada@example.com");
        u.setRole(UserRole.OWNER);
        u.setVerifiedAt(java.time.Instant.now());

        Organization org2 = new Organization();
        org2.setId(u.getOrgId());
        org2.setName("Ada Co");
        org2.setContactEmail("ada@example.com");
        org2.setCountry("GB");

        when(verificationSvc.verify(any(), any())).thenReturn(u);
        when(orgs.findById(u.getOrgId())).thenReturn(java.util.Optional.of(org2));
        when(sessions.save(any(AuthSession.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                com.imin.iminapi.security.ErrorCode.UPSTREAM_UNAVAILABLE, "down"))
                .when(accountEmail).sendWelcome(any());

        com.imin.iminapi.dto.auth.AuthResponse r = sut.verifyEmail(
                new com.imin.iminapi.dto.auth.VerifyEmailRequest("ada@example.com", "1234"));
        assertThat(r.token()).isNotBlank(); // not failed
    }

    // ----- resendVerification -----

    @Test
    void resendVerification_for_unverified_user_issues_new_code_and_sends() {
        User u = new User();
        u.setId(java.util.UUID.randomUUID());
        u.setEmail("ada@example.com");
        u.setVerifiedAt(null);
        when(users.findByEmailLower("ada@example.com")).thenReturn(java.util.Optional.of(u));
        when(verificationSvc.issueCode(u)).thenReturn("9999");

        sut.resendVerification(new com.imin.iminapi.dto.auth.ResendVerificationRequest("ada@example.com"));

        verify(verificationSvc).issueCode(u);
        verify(accountEmail).sendVerificationCode(eq(u), eq("9999"), anyInt());
    }

    @Test
    void resendVerification_for_verified_user_is_a_noop() {
        User u = new User();
        u.setId(java.util.UUID.randomUUID());
        u.setEmail("ada@example.com");
        u.setVerifiedAt(java.time.Instant.now());
        when(users.findByEmailLower("ada@example.com")).thenReturn(java.util.Optional.of(u));

        sut.resendVerification(new com.imin.iminapi.dto.auth.ResendVerificationRequest("ada@example.com"));

        verify(verificationSvc, never()).issueCode(any());
        verify(accountEmail, never()).sendVerificationCode(any(), any(), anyInt());
    }

    @Test
    void resendVerification_for_unknown_email_is_a_noop() {
        when(users.findByEmailLower(any())).thenReturn(java.util.Optional.empty());

        sut.resendVerification(new com.imin.iminapi.dto.auth.ResendVerificationRequest("nobody@example.com"));

        verify(verificationSvc, never()).issueCode(any());
    }

    // ----- forgotPassword -----

    @Test
    void forgotPassword_for_existing_user_issues_token_and_sends_email() {
        User u = new User();
        u.setId(java.util.UUID.randomUUID());
        u.setEmail("ada@example.com");
        when(users.findByEmailLower("ada@example.com")).thenReturn(java.util.Optional.of(u));
        when(passwordResetSvc.issueToken(u)).thenReturn("reset-token-abc");

        sut.forgotPassword(new com.imin.iminapi.dto.auth.ForgotPasswordRequest("ada@example.com"));

        verify(accountEmail).sendPasswordReset(eq(u),
                eq("http://localhost:3000/reset-password?token=reset-token-abc"),
                anyInt());
    }

    @Test
    void forgotPassword_for_unknown_email_is_silent_noop() {
        when(users.findByEmailLower(any())).thenReturn(java.util.Optional.empty());

        sut.forgotPassword(new com.imin.iminapi.dto.auth.ForgotPasswordRequest("nobody@example.com"));

        verify(passwordResetSvc, never()).issueToken(any());
        verify(accountEmail, never()).sendPasswordReset(any(), any(), anyInt());
    }

    @Test
    void forgotPassword_swallows_resend_failure() {
        User u = new User();
        u.setId(java.util.UUID.randomUUID());
        u.setEmail("ada@example.com");
        when(users.findByEmailLower("ada@example.com")).thenReturn(java.util.Optional.of(u));
        when(passwordResetSvc.issueToken(u)).thenReturn("tok");
        doThrow(new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                com.imin.iminapi.security.ErrorCode.UPSTREAM_UNAVAILABLE, "down"))
                .when(accountEmail).sendPasswordReset(any(), any(), anyInt());

        // Should NOT throw — anti-enumeration
        sut.forgotPassword(new com.imin.iminapi.dto.auth.ForgotPasswordRequest("ada@example.com"));
    }

    // ----- resetPassword -----

    @Test
    void resetPassword_consumes_token_revokes_sessions_sends_notification() {
        User u = new User();
        u.setId(java.util.UUID.randomUUID());
        u.setEmail("ada@example.com");
        when(passwordResetSvc.consume("token-abc", "newpassword12")).thenReturn(u);

        sut.resetPassword(new com.imin.iminapi.dto.auth.ResetPasswordRequest("token-abc", "newpassword12"));

        verify(passwordResetSvc).consume("token-abc", "newpassword12");
        verify(sessions).revokeAllForUser(eq(u.getId()), any(java.time.Instant.class));
        verify(accountEmail).sendPasswordChangedNotification(u);
    }

    @Test
    void resetPassword_swallows_notification_email_failure() {
        User u = new User();
        u.setId(java.util.UUID.randomUUID());
        u.setEmail("ada@example.com");
        when(passwordResetSvc.consume(any(), any())).thenReturn(u);
        doThrow(new RuntimeException("email down"))
                .when(accountEmail).sendPasswordChangedNotification(any());

        // Should NOT throw — notification is non-critical
        sut.resetPassword(new com.imin.iminapi.dto.auth.ResetPasswordRequest("token-abc", "newpassword12"));
    }

    private static com.imin.iminapi.email.EmailProperties makeEmailProps() {
        com.imin.iminapi.email.EmailProperties p = new com.imin.iminapi.email.EmailProperties();
        p.setAppBaseUrl("http://localhost:3000");
        return p;
    }
}

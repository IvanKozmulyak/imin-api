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
import static org.mockito.Mockito.*;

class AuthServiceTest {

    OrganizationRepository orgs = mock(OrganizationRepository.class);
    UserRepository users = mock(UserRepository.class);
    AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    PasswordHasher hasher = new PasswordHasher(new BCryptPasswordEncoder(4));
    TokenService tokens = new TokenService();

    AuthService sut = new AuthService(orgs, users, sessions, hasher, tokens, Duration.ofDays(30));

    @Test
    void signup_creates_org_and_owner_then_issues_session() {
        when(users.existsByEmailLower("ada@example.com")).thenReturn(false);
        when(orgs.save(any(Organization.class))).thenAnswer(inv -> {
            Organization o = inv.getArgument(0);
            o.setId(java.util.UUID.randomUUID());
            return o;
        });
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(java.util.UUID.randomUUID());
            return u;
        });
        when(sessions.save(any(AuthSession.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthResponse r = sut.signup(new SignupRequest("ada@example.com", "lovelace12", "Ada Co", "GB"));

        assertThat(r.token()).isNotBlank();
        assertThat(r.user().email()).isEqualTo("ada@example.com");
        assertThat(r.user().role()).isEqualTo("owner");
        assertThat(r.org().name()).isEqualTo("Ada Co");
        assertThat(r.org().country()).isEqualTo("GB");

        verify(orgs).save(any(Organization.class));
        verify(users).save(any(User.class));
        verify(sessions).save(any(AuthSession.class));
    }

    @Test
    void signup_with_existing_email_throws_DUPLICATE() {
        when(users.existsByEmailLower("dupe@example.com")).thenReturn(true);
        assertThatThrownBy(() -> sut.signup(new SignupRequest("dupe@example.com", "valid12345", "X", "FR")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.DUPLICATE);
    }

    @Test
    void avatar_initials_are_derived_from_email_local_part_when_no_name() {
        when(users.existsByEmailLower("ada@example.com")).thenReturn(false);
        when(orgs.save(any(Organization.class))).thenAnswer(inv -> {
            Organization o = inv.getArgument(0); o.setId(java.util.UUID.randomUUID()); return o;
        });
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0); u.setId(java.util.UUID.randomUUID()); return u;
        });
        when(sessions.save(any(AuthSession.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthResponse r = sut.signup(new SignupRequest("ada@example.com", "lovelace12", "X", "GB"));
        assertThat(r.user().avatarInitials()).isEqualTo("AD");
    }

    @Test
    void login_with_valid_password_returns_token() {
        User stored = new User();
        stored.setId(java.util.UUID.randomUUID());
        stored.setOrgId(java.util.UUID.randomUUID());
        stored.setEmail("ada@example.com");
        stored.setPasswordHash(hasher.hash("lovelace12"));
        stored.setRole(UserRole.OWNER);
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
}

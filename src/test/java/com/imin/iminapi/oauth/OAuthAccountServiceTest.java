package com.imin.iminapi.oauth;

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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the OAuth account-linking matrix. The provider round-trip is
 * bypassed by handing {@link OAuthAccountService#resolve} an already-verified
 * {@link OAuthUserInfo}, so no Google/Apple network is touched.
 */
class OAuthAccountServiceTest {

    OrganizationRepository orgs = mock(OrganizationRepository.class);
    UserRepository users = mock(UserRepository.class);
    AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    UserIdentityRepository identities = mock(UserIdentityRepository.class);
    TokenService tokens = new TokenService();

    OAuthAccountService sut = new OAuthAccountService(orgs, users, sessions, identities, tokens,
            Duration.ofDays(30), "NL");

    private OAuthUserInfo google(String sub, String email, boolean verified) {
        return new OAuthUserInfo("google", sub, email, verified, "Ada", "Lovelace", "Ada Lovelace");
    }

    private void stubSessionSave() {
        when(sessions.save(any(AuthSession.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void known_identity_logs_in_existing_user_without_creating_anything() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UserIdentity id = new UserIdentity();
        id.setUserId(userId);
        User u = new User();
        u.setId(userId); u.setOrgId(orgId); u.setEmail("ada@example.com");
        u.setRole(UserRole.OWNER); u.setVerifiedAt(Instant.now());
        Organization org = new Organization();
        org.setId(orgId); org.setName("Ada Co"); org.setContactEmail("ada@example.com"); org.setCountry("GB");

        when(identities.findByProviderAndProviderUserId("google", "sub-1")).thenReturn(Optional.of(id));
        when(users.findById(userId)).thenReturn(Optional.of(u));
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        stubSessionSave();

        AuthResponse r = sut.resolve(google("sub-1", "ada@example.com", true));

        assertThat(r.token()).isNotBlank();
        assertThat(r.user().id()).isEqualTo(userId);
        verify(identities, never()).save(any(UserIdentity.class));
        verify(orgs, never()).saveAndFlush(any(Organization.class));
    }

    @Test
    void verified_email_links_to_existing_user_and_backfills_verified_at() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        User u = new User();
        u.setId(userId); u.setOrgId(orgId); u.setEmail("ada@example.com");
        u.setRole(UserRole.OWNER); u.setVerifiedAt(null); // was never verified via email
        Organization org = new Organization();
        org.setId(orgId); org.setName("Ada Co"); org.setContactEmail("ada@example.com"); org.setCountry("GB");

        when(identities.findByProviderAndProviderUserId("google", "sub-2")).thenReturn(Optional.empty());
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));
        when(orgs.findById(orgId)).thenReturn(Optional.of(org));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        AtomicReference<UserIdentity> saved = new AtomicReference<>();
        when(identities.save(any(UserIdentity.class))).thenAnswer(inv -> { saved.set(inv.getArgument(0)); return inv.getArgument(0); });
        stubSessionSave();

        AuthResponse r = sut.resolve(google("sub-2", "ada@example.com", true));

        assertThat(r.token()).isNotBlank();
        assertThat(u.getVerifiedAt()).isNotNull();                 // backfilled
        assertThat(saved.get().getUserId()).isEqualTo(userId);
        assertThat(saved.get().getProvider()).isEqualTo("google");
        assertThat(saved.get().getProviderUserId()).isEqualTo("sub-2");
        verify(orgs, never()).saveAndFlush(any(Organization.class)); // no new account
    }

    @Test
    void verified_email_no_user_provisions_org_and_owner() {
        AtomicReference<Organization> savedOrg = new AtomicReference<>();
        AtomicReference<User> savedUser = new AtomicReference<>();

        when(identities.findByProviderAndProviderUserId("google", "sub-3")).thenReturn(Optional.empty());
        when(users.findByEmailLower("new@example.com")).thenReturn(Optional.empty());
        when(orgs.existsBySlug(any())).thenReturn(false);
        when(orgs.saveAndFlush(any(Organization.class))).thenAnswer(inv -> {
            Organization o = inv.getArgument(0); o.setId(UUID.randomUUID()); savedOrg.set(o); return o;
        });
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0); if (u.getId() == null) u.setId(UUID.randomUUID()); savedUser.set(u); return u;
        });
        when(orgs.findById(any())).thenAnswer(inv -> Optional.of(savedOrg.get()));
        when(identities.save(any(UserIdentity.class))).thenAnswer(inv -> inv.getArgument(0));
        stubSessionSave();

        AuthResponse r = sut.resolve(google("sub-3", "new@example.com", true));

        assertThat(r.token()).isNotBlank();
        assertThat(savedUser.get().getRole()).isEqualTo(UserRole.OWNER);
        assertThat(savedUser.get().getPasswordHash()).isNull();        // OAuth-only account
        assertThat(savedUser.get().getVerifiedAt()).isNotNull();       // provider-authenticated
        assertThat(savedUser.get().getAvatarInitials()).isEqualTo("AL");
        assertThat(savedOrg.get().getName()).isEqualTo("Ada's events");
        assertThat(savedOrg.get().getCountry()).isEqualTo("NL");       // configured default
        verify(identities).save(any(UserIdentity.class));
    }

    @Test
    void unverified_email_existing_user_throws_conflict_and_does_not_link() {
        User u = new User();
        u.setId(UUID.randomUUID()); u.setOrgId(UUID.randomUUID());
        u.setEmail("ada@example.com"); u.setRole(UserRole.OWNER);

        when(identities.findByProviderAndProviderUserId("google", "sub-4")).thenReturn(Optional.empty());
        when(users.findByEmailLower("ada@example.com")).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> sut.resolve(google("sub-4", "ada@example.com", false)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OAUTH_EMAIL_CONFLICT);
        verify(identities, never()).save(any(UserIdentity.class));
        verify(sessions, never()).save(any(AuthSession.class));
    }

    @Test
    void unverified_email_no_user_still_provisions_a_separate_account() {
        AtomicReference<User> savedUser = new AtomicReference<>();
        when(identities.findByProviderAndProviderUserId("google", "sub-5")).thenReturn(Optional.empty());
        when(users.findByEmailLower("solo@example.com")).thenReturn(Optional.empty());
        when(orgs.existsBySlug(any())).thenReturn(false);
        AtomicReference<Organization> savedOrg = new AtomicReference<>();
        when(orgs.saveAndFlush(any(Organization.class))).thenAnswer(inv -> {
            Organization o = inv.getArgument(0); o.setId(UUID.randomUUID()); savedOrg.set(o); return o;
        });
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0); if (u.getId() == null) u.setId(UUID.randomUUID()); savedUser.set(u); return u;
        });
        when(orgs.findById(any())).thenAnswer(inv -> Optional.of(savedOrg.get()));
        when(identities.save(any(UserIdentity.class))).thenAnswer(inv -> inv.getArgument(0));
        stubSessionSave();

        AuthResponse r = sut.resolve(google("sub-5", "solo@example.com", false));

        assertThat(r.token()).isNotBlank();
        assertThat(savedUser.get().getEmail()).isEqualTo("solo@example.com");
        verify(identities).save(any(UserIdentity.class));
    }

    @Test
    void blank_email_throws_email_required() {
        when(identities.findByProviderAndProviderUserId("google", "sub-6")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.resolve(google("sub-6", "", true)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OAUTH_EMAIL_REQUIRED);
        verify(users, never()).save(any(User.class));
        verify(orgs, never()).saveAndFlush(any(Organization.class));
    }

    @Test
    void null_email_throws_email_required() {
        when(identities.findByProviderAndProviderUserId("apple", "sub-7")).thenReturn(Optional.empty());
        OAuthUserInfo info = new OAuthUserInfo("apple", "sub-7", null, true, "", "", null);

        assertThatThrownBy(() -> sut.resolve(info))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.OAUTH_EMAIL_REQUIRED);
    }

    @Test
    void provision_falls_back_to_my_events_when_provider_gives_no_name() {
        AtomicReference<Organization> savedOrg = new AtomicReference<>();
        when(identities.findByProviderAndProviderUserId("apple", "sub-8")).thenReturn(Optional.empty());
        when(users.findByEmailLower("anon@privaterelay.appleid.com")).thenReturn(Optional.empty());
        when(orgs.existsBySlug(any())).thenReturn(false);
        when(orgs.saveAndFlush(any(Organization.class))).thenAnswer(inv -> {
            Organization o = inv.getArgument(0); o.setId(UUID.randomUUID()); savedOrg.set(o); return o;
        });
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0); if (u.getId() == null) u.setId(UUID.randomUUID()); return u;
        });
        when(orgs.findById(any())).thenAnswer(inv -> Optional.of(savedOrg.get()));
        when(identities.save(any(UserIdentity.class))).thenAnswer(inv -> inv.getArgument(0));
        stubSessionSave();

        OAuthUserInfo info = new OAuthUserInfo("apple", "sub-8",
                "anon@privaterelay.appleid.com", true, "", "", null);
        AuthResponse r = sut.resolve(info);

        assertThat(r.token()).isNotBlank();
        assertThat(savedOrg.get().getName()).isEqualTo("My events");
    }
}

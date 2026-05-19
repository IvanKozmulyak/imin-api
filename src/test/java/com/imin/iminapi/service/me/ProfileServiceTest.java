package com.imin.iminapi.service.me;

import com.imin.iminapi.dto.auth.MeResponse;
import com.imin.iminapi.dto.me.ProfilePatchRequest;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProfileServiceTest {

    UserRepository users = mock(UserRepository.class);
    OrganizationRepository orgs = mock(OrganizationRepository.class);
    ProfileService sut = new ProfileService(users, orgs);

    private User existingUser(UUID userId, UUID orgId, String firstName, String lastName, String initials) {
        User u = new User();
        u.setId(userId);
        u.setOrgId(orgId);
        u.setEmail("ada@example.com");
        u.setRole(UserRole.OWNER);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setAvatarInitials(initials);
        return u;
    }

    private Organization sampleOrg(UUID orgId) {
        Organization o = new Organization();
        o.setId(orgId);
        o.setName("Ada Co");
        o.setContactEmail("a@b.c");
        o.setCountry("GB");
        o.setTimezone("UTC");
        return o;
    }

    private AuthPrincipal principal(UUID userId, UUID orgId) {
        return new AuthPrincipal(userId, orgId, UserRole.OWNER, UUID.randomUUID());
    }

    @Test
    void patch_updates_first_and_last_name_and_recomputes_initials() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        User u = existingUser(userId, orgId, "Ada", "Lovelace", "AL");
        AtomicReference<User> saved = new AtomicReference<>();
        when(users.findById(userId)).thenReturn(Optional.of(u));
        when(users.save(any(User.class))).thenAnswer(inv -> { saved.set(inv.getArgument(0)); return inv.getArgument(0); });
        when(orgs.findById(orgId)).thenReturn(Optional.of(sampleOrg(orgId)));

        MeResponse resp = sut.patch(principal(userId, orgId),
                new ProfilePatchRequest("Grace", "Hopper"));

        assertThat(saved.get()).isNotNull();
        assertThat(saved.get().getFirstName()).isEqualTo("Grace");
        assertThat(saved.get().getLastName()).isEqualTo("Hopper");
        assertThat(saved.get().getAvatarInitials()).isEqualTo("GH");
        assertThat(resp.user().firstName()).isEqualTo("Grace");
        assertThat(resp.user().lastName()).isEqualTo("Hopper");
        assertThat(resp.user().avatarInitials()).isEqualTo("GH");
    }

    @Test
    void patch_with_null_field_leaves_it_unchanged() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        User u = existingUser(userId, orgId, "Ada", "Lovelace", "AL");
        AtomicReference<User> saved = new AtomicReference<>();
        when(users.findById(userId)).thenReturn(Optional.of(u));
        when(users.save(any(User.class))).thenAnswer(inv -> { saved.set(inv.getArgument(0)); return inv.getArgument(0); });
        when(orgs.findById(orgId)).thenReturn(Optional.of(sampleOrg(orgId)));

        MeResponse resp = sut.patch(principal(userId, orgId),
                new ProfilePatchRequest("Grace", null));

        assertThat(saved.get().getFirstName()).isEqualTo("Grace");
        assertThat(saved.get().getLastName()).isEqualTo("Lovelace"); // untouched
        assertThat(saved.get().getAvatarInitials()).isEqualTo("GL");
        assertThat(resp.user().lastName()).isEqualTo("Lovelace");
    }

    @Test
    void patch_trims_whitespace() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        User u = existingUser(userId, orgId, "Ada", "Lovelace", "AL");
        AtomicReference<User> saved = new AtomicReference<>();
        when(users.findById(userId)).thenReturn(Optional.of(u));
        when(users.save(any(User.class))).thenAnswer(inv -> { saved.set(inv.getArgument(0)); return inv.getArgument(0); });
        when(orgs.findById(orgId)).thenReturn(Optional.of(sampleOrg(orgId)));

        sut.patch(principal(userId, orgId),
                new ProfilePatchRequest("  Grace  ", "\tHopper\n"));

        assertThat(saved.get().getFirstName()).isEqualTo("Grace");
        assertThat(saved.get().getLastName()).isEqualTo("Hopper");
    }

    @Test
    void patch_empty_string_after_trim_throws_FIELD_INVALID() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.of(existingUser(userId, orgId, "Ada", "Lovelace", "AL")));

        assertThatThrownBy(() -> sut.patch(principal(userId, orgId),
                new ProfilePatchRequest("   ", null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
        verify(users, never()).save(any(User.class));
    }

    @Test
    void patch_field_longer_than_limit_throws_FIELD_INVALID() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.of(existingUser(userId, orgId, "Ada", "Lovelace", "AL")));
        String tooLong = "x".repeat(ProfileService.MAX_NAME_LENGTH + 1);

        assertThatThrownBy(() -> sut.patch(principal(userId, orgId),
                new ProfilePatchRequest(tooLong, null)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FIELD_INVALID);
        verify(users, never()).save(any(User.class));
    }

    @Test
    void patch_with_no_changes_skips_save() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.of(existingUser(userId, orgId, "Ada", "Lovelace", "AL")));
        when(orgs.findById(orgId)).thenReturn(Optional.of(sampleOrg(orgId)));

        sut.patch(principal(userId, orgId), new ProfilePatchRequest(null, null));
        verify(users, never()).save(any(User.class));

        // Same values as existing → also no save
        sut.patch(principal(userId, orgId), new ProfilePatchRequest("Ada", "Lovelace"));
        verify(users, never()).save(any(User.class));
    }

    @Test
    void patch_unknown_user_throws_NOT_FOUND() {
        UUID userId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.patch(principal(userId, UUID.randomUUID()),
                new ProfilePatchRequest("Grace", "Hopper")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.NOT_FOUND);
    }
}

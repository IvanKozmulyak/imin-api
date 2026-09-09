package com.imin.iminapi.service.org;

import com.imin.iminapi.dto.org.InviteRequest;
import com.imin.iminapi.dto.org.InviteResponse;
import com.imin.iminapi.dto.org.TeamMemberDto;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AuthSessionRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TeamServiceTest {

    UserRepository users = mock(UserRepository.class);
    AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    TeamService sut = new TeamService(users, sessions);

    private AuthPrincipal admin(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.ADMIN, UUID.randomUUID());
    }
    private AuthPrincipal owner(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }
    private AuthPrincipal member(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.MEMBER, UUID.randomUUID());
    }

    @Test
    void list_returns_org_members() {
        UUID orgId = UUID.randomUUID();
        User u = new User(); u.setId(UUID.randomUUID()); u.setOrgId(orgId);
        u.setEmail("a@b.com"); u.setRole(UserRole.OWNER);
        when(users.findByOrgIdAndDisabledAtIsNullOrderByCreatedAtAsc(orgId)).thenReturn(List.of(u));

        List<TeamMemberDto> r = sut.list(admin(orgId));
        assertThat(r).hasSize(1);
        assertThat(r.get(0).email()).isEqualTo("a@b.com");
    }

    @Test
    void invite_creates_user_with_no_password_hash_and_returns_inviteId() {
        UUID orgId = UUID.randomUUID();
        when(users.findByEmailLower("new@x.com")).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0); u.setId(UUID.randomUUID()); return u;
        });

        InviteResponse r = sut.invite(admin(orgId), new InviteRequest("New@X.com", "member"));
        assertThat(r.email()).isEqualTo("New@X.com");
        assertThat(r.role()).isEqualTo("member");
        assertThat(r.inviteId()).isNotNull();
    }

    @Test
    void invite_existing_email_returns_DUPLICATE() {
        UUID orgId = UUID.randomUUID();
        User existing = new User();
        existing.setId(UUID.randomUUID()); existing.setOrgId(orgId); existing.setRole(UserRole.MEMBER);
        when(users.findByEmailLower("dupe@x.com")).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> sut.invite(admin(orgId), new InviteRequest("dupe@x.com", "member")))
                .hasFieldOrPropertyWithValue("code", ErrorCode.DUPLICATE);
    }

    @Test
    void remove_owner_throws_FORBIDDEN() {
        UUID orgId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        User own = new User(); own.setId(ownerId); own.setOrgId(orgId); own.setRole(UserRole.OWNER);
        when(users.findById(ownerId)).thenReturn(Optional.of(own));

        assertThatThrownBy(() -> sut.remove(owner(orgId), ownerId))
                .hasFieldOrPropertyWithValue("code", ErrorCode.FORBIDDEN);
    }

    @Test
    void remove_member_in_other_org_returns_NOT_FOUND() {
        UUID orgId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        User u = new User(); u.setId(otherUser); u.setOrgId(UUID.randomUUID()); u.setRole(UserRole.MEMBER);
        when(users.findById(otherUser)).thenReturn(Optional.of(u));
        assertThatThrownBy(() -> sut.remove(admin(orgId), otherUser))
                .hasFieldOrPropertyWithValue("code", ErrorCode.NOT_FOUND);
    }

    // ---- Within-org role gate (api-1) ------------------------------------

    @Test
    void member_cannot_invite() {
        UUID orgId = UUID.randomUUID();
        assertThatThrownBy(() -> sut.invite(member(orgId), new InviteRequest("new@x.com", "member")))
                .hasFieldOrPropertyWithValue("code", ErrorCode.FORBIDDEN);
        // Refused before the address is probed — otherwise the 409/200 split is an
        // account-existence oracle for anyone with a login.
        verify(users, never()).findByEmailLower(anyString());
        verify(users, never()).save(any(User.class));
    }

    @Test
    void member_cannot_invite_an_admin() {
        UUID orgId = UUID.randomUUID();
        assertThatThrownBy(() -> sut.invite(member(orgId), new InviteRequest("new@x.com", "admin")))
                .hasFieldOrPropertyWithValue("code", ErrorCode.FORBIDDEN);
        verify(users, never()).save(any(User.class));
    }

    @Test
    void admin_cannot_invite_an_owner() {
        UUID orgId = UUID.randomUUID();
        assertThatThrownBy(() -> sut.invite(admin(orgId), new InviteRequest("new@x.com", "owner")))
                .hasFieldOrPropertyWithValue("code", ErrorCode.FORBIDDEN);
        verify(users, never()).save(any(User.class));
    }

    @Test
    void admin_can_invite_a_peer_admin() {
        UUID orgId = UUID.randomUUID();
        when(users.findByEmailLower("peer@x.com")).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0); u.setId(UUID.randomUUID()); return u;
        });
        InviteResponse r = sut.invite(admin(orgId), new InviteRequest("peer@x.com", "admin"));
        assertThat(r.role()).isEqualTo("admin");
    }

    @Test
    void member_cannot_remove() {
        UUID orgId = UUID.randomUUID();
        assertThatThrownBy(() -> sut.remove(member(orgId), UUID.randomUUID()))
                .hasFieldOrPropertyWithValue("code", ErrorCode.FORBIDDEN);
        verify(users, never()).delete(any(User.class));
    }

    @Test
    void gate_device_cannot_invite_or_remove() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal gate = AuthPrincipal.forGate(orgId, UUID.randomUUID());
        assertThatThrownBy(() -> sut.invite(gate, new InviteRequest("new@x.com", "member")))
                .hasFieldOrPropertyWithValue("code", ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> sut.remove(gate, UUID.randomUUID()))
                .hasFieldOrPropertyWithValue("code", ErrorCode.FORBIDDEN);
    }

    /** The org always keeps an owner: no owner may be removed, so the last one cannot be either. */
    @Test
    void owner_cannot_remove_the_last_owner() {
        UUID orgId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        User own = new User(); own.setId(ownerId); own.setOrgId(orgId); own.setRole(UserRole.OWNER);
        when(users.findById(ownerId)).thenReturn(Optional.of(own));
        assertThatThrownBy(() -> sut.remove(owner(orgId), ownerId))
                .hasFieldOrPropertyWithValue("code", ErrorCode.FORBIDDEN);
        verify(users, never()).delete(any(User.class));
    }

    @Test
    void remove_member_with_no_references_deletes_row() {
        UUID orgId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        User u = new User(); u.setId(memberId); u.setOrgId(orgId); u.setRole(UserRole.MEMBER);
        when(users.findById(memberId)).thenReturn(Optional.of(u));
        when(users.countRetainedReferences(memberId)).thenReturn(0L);
        sut.remove(admin(orgId), memberId);
        verify(users).delete(u);
        verify(sessions).revokeAllForUser(eq(memberId), any());
    }

    // ---- Soft delete when RESTRICT FKs point at the row (data-2) -----------

    @Test
    void remove_member_who_created_an_event_disables_instead_of_deleting() {
        UUID orgId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        User u = new User(); u.setId(memberId); u.setOrgId(orgId); u.setRole(UserRole.MEMBER);
        when(users.findById(memberId)).thenReturn(Optional.of(u));
        when(users.countRetainedReferences(memberId)).thenReturn(1L);

        sut.remove(admin(orgId), memberId);

        // The hard delete would have raised 23503 (events.created_by is RESTRICT)
        // and surfaced as an unexplained 400.
        verify(users, never()).delete(any(User.class));
        verify(users).save(u);
        assertThat(u.getDisabledAt()).isNotNull();
        verify(sessions).revokeAllForUser(eq(memberId), any());
    }

    @Test
    void re_inviting_a_removed_member_reactivates_them_instead_of_409() {
        UUID orgId = UUID.randomUUID();
        User prior = new User();
        prior.setId(UUID.randomUUID()); prior.setOrgId(orgId);
        prior.setEmail("back@x.com"); prior.setRole(UserRole.MEMBER);
        prior.setDisabledAt(java.time.Instant.now());
        when(users.findByEmailLower("back@x.com")).thenReturn(Optional.of(prior));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        InviteResponse r = sut.invite(owner(orgId), new InviteRequest("back@x.com", "admin"));

        assertThat(prior.getDisabledAt()).isNull();
        assertThat(r.role()).isEqualTo("admin");
        assertThat(r.inviteId()).isEqualTo(prior.getId());
    }

    @Test
    void re_inviting_a_disabled_user_from_another_org_still_409s() {
        UUID orgId = UUID.randomUUID();
        User prior = new User();
        prior.setId(UUID.randomUUID()); prior.setOrgId(UUID.randomUUID());
        prior.setEmail("elsewhere@x.com"); prior.setRole(UserRole.MEMBER);
        prior.setDisabledAt(java.time.Instant.now());
        when(users.findByEmailLower("elsewhere@x.com")).thenReturn(Optional.of(prior));
        assertThatThrownBy(() -> sut.invite(owner(orgId), new InviteRequest("elsewhere@x.com", "member")))
                .hasFieldOrPropertyWithValue("code", ErrorCode.DUPLICATE);
    }
}

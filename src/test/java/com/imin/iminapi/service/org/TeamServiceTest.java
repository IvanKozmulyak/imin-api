package com.imin.iminapi.service.org;

import com.imin.iminapi.dto.org.InviteRequest;
import com.imin.iminapi.dto.org.InviteResponse;
import com.imin.iminapi.dto.org.TeamMemberDto;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
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
import static org.mockito.Mockito.*;

class TeamServiceTest {

    UserRepository users = mock(UserRepository.class);
    TeamService sut = new TeamService(users);

    private AuthPrincipal admin(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.ADMIN, UUID.randomUUID());
    }
    private AuthPrincipal owner(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    @Test
    void list_returns_org_members() {
        UUID orgId = UUID.randomUUID();
        User u = new User(); u.setId(UUID.randomUUID()); u.setOrgId(orgId);
        u.setEmail("a@b.com"); u.setRole(UserRole.OWNER);
        when(users.findByOrgIdOrderByCreatedAtAsc(orgId)).thenReturn(List.of(u));

        List<TeamMemberDto> r = sut.list(admin(orgId));
        assertThat(r).hasSize(1);
        assertThat(r.get(0).email()).isEqualTo("a@b.com");
    }

    @Test
    void invite_creates_user_with_no_password_hash_and_returns_inviteId() {
        UUID orgId = UUID.randomUUID();
        when(users.existsByEmailLower("new@x.com")).thenReturn(false);
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
        when(users.existsByEmailLower("dupe@x.com")).thenReturn(true);
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

    @Test
    void remove_member_deletes_row() {
        UUID orgId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        User u = new User(); u.setId(memberId); u.setOrgId(orgId); u.setRole(UserRole.MEMBER);
        when(users.findById(memberId)).thenReturn(Optional.of(u));
        sut.remove(admin(orgId), memberId);
        verify(users).delete(u);
    }
}

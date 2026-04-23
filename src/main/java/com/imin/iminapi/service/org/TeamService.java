package com.imin.iminapi.service.org;

import com.imin.iminapi.dto.org.InviteRequest;
import com.imin.iminapi.dto.org.InviteResponse;
import com.imin.iminapi.dto.org.TeamMemberDto;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TeamService {

    private final UserRepository users;

    public TeamService(UserRepository users) { this.users = users; }

    @Transactional(readOnly = true)
    public List<TeamMemberDto> list(AuthPrincipal p) {
        return users.findByOrgIdOrderByCreatedAtAsc(p.orgId()).stream().map(TeamMemberDto::from).toList();
    }

    @Transactional
    public InviteResponse invite(AuthPrincipal p, InviteRequest req) {
        String emailLower = req.email().toLowerCase();
        if (users.existsByEmailLower(emailLower)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE,
                    "Email already in use", Map.of("email", "already in use"));
        }
        User u = new User();
        u.setOrgId(p.orgId());
        u.setEmail(req.email());
        u.setName("");
        u.setRole(UserRole.fromWire(req.role()));
        u.setAvatarInitials(initialsOf(req.email()));
        u.setPasswordHash(null); // pending until invite-accept (post-V1)
        User saved = users.save(u);
        return new InviteResponse(saved.getId(), saved.getEmail(), saved.getRole().wireValue());
    }

    @Transactional
    public void remove(AuthPrincipal p, UUID userId) {
        User target = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (!target.getOrgId().equals(p.orgId())) throw ApiException.notFound("User");
        if (target.getRole() == UserRole.OWNER) throw ApiException.forbidden("Cannot remove the org owner");
        users.delete(target);
    }

    private static String initialsOf(String email) {
        if (email == null || email.isBlank()) return "";
        int at = email.indexOf('@');
        String src = at > 0 ? email.substring(0, at) : email;
        return src.length() <= 1 ? src.toUpperCase() : src.substring(0, 2).toUpperCase();
    }
}

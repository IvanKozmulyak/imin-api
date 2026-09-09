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
import com.imin.iminapi.security.RoleGuard;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TeamService {

    private final UserRepository users;
    /** Optional audit logger — null in older test constructors. */
    private final AuditLogger auditLogger;

    /** Legacy 1-arg constructor for existing tests. */
    public TeamService(UserRepository users) { this(users, null); }

    /** Primary constructor — Spring uses this one. */
    @org.springframework.beans.factory.annotation.Autowired
    public TeamService(UserRepository users, AuditLogger auditLogger) {
        this.users = users;
        this.auditLogger = auditLogger;
    }

    private void audit(AuthPrincipal p, String action, String targetType, UUID targetId, String summary) {
        if (auditLogger != null) auditLogger.record(p, action, targetType, targetId, summary);
    }

    @Transactional(readOnly = true)
    public List<TeamMemberDto> list(AuthPrincipal p) {
        return users.findByOrgIdOrderByCreatedAtAsc(p.orgId()).stream().map(TeamMemberDto::from).toList();
    }

    /**
     * Invites a new team member.
     *
     * <p>Both gates run before anything else. The role gate is first because a
     * MEMBER minting an ADMIN was full privilege escalation: the invited row is
     * created unverified and with no password hash, so whoever controls the
     * address completes it through the public verify-email flow and walks away
     * with a persistent ADMIN the org never approved. The grant gate is second
     * because "may invite" and "may invite <i>at this level</i>" are different
     * questions — an ADMIN inviting an OWNER would grow a peer above them.
     *
     * <p>Both fire before {@code existsByEmailLower}, so a caller who may not
     * invite cannot use the 409-vs-200 split as an account-existence oracle.
     */
    @Transactional
    public InviteResponse invite(AuthPrincipal p, InviteRequest req) {
        RoleGuard.requireAtLeast(p, UserRole.ADMIN, "invite team members");
        UserRole granted = UserRole.fromWire(req.role());
        RoleGuard.requireCanGrant(p, granted, "invite a team member");
        String emailLower = req.email().toLowerCase();
        if (users.existsByEmailLower(emailLower)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE,
                    "Email already in use", Map.of("email", "already in use"));
        }
        User u = new User();
        u.setOrgId(p.orgId());
        u.setEmail(req.email());
        u.setFirstName("");
        u.setLastName("");
        u.setRole(granted);
        u.setAvatarInitials(initialsOf(req.email()));
        u.setPasswordHash(null); // pending until invite-accept (post-V1)
        User saved = users.save(u);
        audit(p, AuditActions.MEMBER_INVITED, "user", saved.getId(),
                "Invited " + saved.getEmail() + " as " + saved.getRole().wireValue());
        return new InviteResponse(saved.getId(), saved.getEmail(), saved.getRole().wireValue());
    }

    /**
     * Removes a team member.
     *
     * <p>The role gate comes before the lookup so a MEMBER gets the same 403 for
     * every id and learns nothing about who exists. Cross-org targets keep the
     * leak-safe 404 the rest of the organizer surface returns
     * ({@code CrossOrgScopingTest}), and no OWNER may be removed at all — which
     * is what stops the org being left without one.
     */
    @Transactional
    public void remove(AuthPrincipal p, UUID userId) {
        RoleGuard.requireAtLeast(p, UserRole.ADMIN, "remove team members");
        User target = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (!target.getOrgId().equals(p.orgId())) throw ApiException.notFound("User");
        if (target.getRole() == UserRole.OWNER) throw ApiException.forbidden("Cannot remove the org owner");
        // An ADMIN may not remove a peer ADMIN — only an OWNER outranks one.
        RoleGuard.requireCanGrant(p, target.getRole(), "remove a team member");
        String removedEmail = target.getEmail();
        UUID removedId = target.getId();
        users.delete(target);
        audit(p, AuditActions.MEMBER_REMOVED, "user", removedId,
                "Removed " + removedEmail);
    }

    private static String initialsOf(String email) {
        if (email == null || email.isBlank()) return "";
        int at = email.indexOf('@');
        String src = at > 0 ? email.substring(0, at) : email;
        return src.length() <= 1 ? src.toUpperCase() : src.substring(0, 2).toUpperCase();
    }
}

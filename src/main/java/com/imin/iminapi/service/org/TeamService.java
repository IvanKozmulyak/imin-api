package com.imin.iminapi.service.org;

import com.imin.iminapi.dto.org.InviteRequest;
import com.imin.iminapi.dto.org.InviteResponse;
import com.imin.iminapi.dto.org.TeamMemberDto;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AuthSessionRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TeamService {

    private final UserRepository users;
    private final AuthSessionRepository sessions;
    /** Optional audit logger — null in older test constructors. */
    private final AuditLogger auditLogger;

    public TeamService(UserRepository users, AuthSessionRepository sessions) {
        this(users, sessions, null);
    }

    /** Primary constructor — Spring uses this one. */
    @org.springframework.beans.factory.annotation.Autowired
    public TeamService(UserRepository users, AuthSessionRepository sessions, AuditLogger auditLogger) {
        this.users = users;
        this.sessions = sessions;
        this.auditLogger = auditLogger;
    }

    private void audit(AuthPrincipal p, String action, String targetType, UUID targetId, String summary) {
        if (auditLogger != null) auditLogger.record(p, action, targetType, targetId, summary);
    }

    @Transactional(readOnly = true)
    public List<TeamMemberDto> list(AuthPrincipal p) {
        return users.findByOrgIdAndDisabledAtIsNullOrderByCreatedAtAsc(p.orgId())
                .stream().map(TeamMemberDto::from).toList();
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
     * <p>Both fire before the address is looked up, so a caller who may not
     * invite cannot use the 409-vs-200 split as an account-existence oracle.
     */
    @Transactional
    public InviteResponse invite(AuthPrincipal p, InviteRequest req) {
        RoleGuard.requireAtLeast(p, UserRole.ADMIN, "invite team members");
        UserRole granted = UserRole.fromWire(req.role());
        RoleGuard.requireCanGrant(p, granted, "invite a team member");
        String emailLower = req.email().toLowerCase();
        java.util.Optional<User> existing = users.findByEmailLower(emailLower);
        if (existing.isPresent()) {
            User prior = existing.get();
            // Re-inviting somebody who was removed must bring them back, not 409
            // for ever: the row survives removal (V118) and the address stays
            // unique, so without this the org could never re-add a returning
            // colleague. Only within the caller's own org, and only a row that
            // is actually disabled.
            if (prior.getDisabledAt() != null && p.orgId().equals(prior.getOrgId())) {
                prior.setDisabledAt(null);
                prior.setRole(granted);
                User revived = users.save(prior);
                audit(p, AuditActions.MEMBER_INVITED, "user", revived.getId(),
                        "Re-invited " + revived.getEmail() + " as " + revived.getRole().wireValue());
                return new InviteResponse(revived.getId(), revived.getEmail(), revived.getRole().wireValue());
            }
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
        // Revoke first, so the window between "removed" and "cannot authenticate"
        // is zero either way this goes.
        sessions.revokeAllForUser(removedId, Instant.now());
        if (users.countRetainedReferences(removedId) > 0) {
            // events.created_by / refunds.initiated_by_user_id /
            // refund_requests.decided_by_user_id are RESTRICT, so deleting this row
            // raises 23503 at commit and the organizer gets an unexplained 400 —
            // permanently, for exactly the member worth removing. Disable instead:
            // the account cannot authenticate (BearerTokenAuthFilter, and both
            // issueSession paths) and leaves the team list, while who-created-what
            // stays answerable.
            target.setDisabledAt(Instant.now());
            users.save(target);
        } else {
            // Nothing references them — an invite that was never accepted, say —
            // so the row can go and leave no trace.
            users.delete(target);
        }
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

package com.imin.iminapi.service.audit;

import com.imin.iminapi.model.AuditLog;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.AuditLogRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Captures meaningful organizer-side mutations to {@code audit_logs}.
 *
 * <p>Write semantics:
 * <ul>
 *   <li>Each {@link #record} call runs in its own transaction
 *       ({@link Propagation#REQUIRES_NEW}) so an audit-write failure can never
 *       roll back the surrounding business transaction.</li>
 *   <li>Any exception thrown by the repository (or anything else) is caught,
 *       logged at ERROR level, and swallowed. Audit writes are best-effort —
 *       losing an audit row is strictly better than failing the user's action.</li>
 *   <li>Actor email is resolved lazily from {@link UserRepository}. A cache miss
 *       leaves {@code actorEmail = null} and logs a warning instead of throwing.</li>
 * </ul>
 */
@Service
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    private final AuditLogRepository auditLogs;
    private final UserRepository users;

    public AuditLogger(AuditLogRepository auditLogs, UserRepository users) {
        this.auditLogs = auditLogs;
        this.users = users;
    }

    /**
     * Persist a single audit row.
     *
     * @param principal the acting user (org_id and user_id come from here)
     * @param action a constant from {@link AuditActions}
     * @param targetType short type name (e.g. "event", "tier", "promo", "user"), nullable
     * @param targetId optional id of the affected resource
     * @param summary human-readable, one-line description (≤ 512 chars)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuthPrincipal principal, String action, String targetType,
                       UUID targetId, String summary) {
        try {
            AuditLog row = new AuditLog();
            row.setOrgId(principal.orgId());
            row.setActorId(principal.userId());
            row.setActorEmail(lookupEmail(principal.userId()));
            row.setAction(action);
            row.setTargetType(targetType);
            row.setTargetId(targetId);
            row.setSummary(summary == null ? "" : summary);
            auditLogs.save(row);
        } catch (RuntimeException e) {
            // Never rethrow — audit failures must not break the surrounding business txn.
            log.error("Audit write failed action={} target={} org={} actor={}: {}",
                    action, targetType, principal.orgId(), principal.userId(), e.getMessage(), e);
        }
    }

    private String lookupEmail(UUID userId) {
        if (userId == null) return null;
        try {
            Optional<User> u = users.findById(userId);
            if (u.isEmpty()) {
                log.warn("Audit actor user not found: id={}", userId);
                return null;
            }
            return u.get().getEmail();
        } catch (RuntimeException e) {
            log.warn("Audit actor lookup failed id={}: {}", userId, e.getMessage());
            return null;
        }
    }
}

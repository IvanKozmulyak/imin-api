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
     * @param summary human-readable, one-line description (≤ 512 chars; truncated if longer)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuthPrincipal principal, String action, String targetType,
                       UUID targetId, String summary) {
        // ── Validate BEFORE writing, because a failed write here is not
        // recoverable by the catch below. ────────────────────────────────────
        //
        // The try/catch cannot save us from a constraint violation. This method
        // runs in its own REQUIRES_NEW transaction; a failing INSERT marks that
        // transaction rollback-only, and the commit — performed by the
        // transaction interceptor AFTER this method has returned — then throws
        // UnexpectedRollbackException straight into the caller's frame, rolling
        // back its business transaction. The class contract below says audit
        // writes are best-effort and swallowed; the only way to actually honour
        // that is to never emit a statement that cannot succeed.
        //
        // This is not hypothetical. audit_logs.org_id is NOT NULL (V21:3) and
        // AudienceErasureJob passed a SYSTEM principal with a null org, so
        // DsarService.executeErase aborted on every run: no tombstone was
        // written AND no membership was ever erased. Every test that covered
        // the cascade mocked this class, so nothing caught it for months.
        if (principal == null || principal.orgId() == null) {
            // Same reasoning ConsentService documents for its null-principal
            // captures: an org-less audit row cannot be stored (NOT NULL) and
            // could not be found if it were, since the index and every reader
            // are org-scoped. Callers with no org must attribute the row to the
            // org whose data they are touching — see AudienceErasureJob.
            log.warn("Audit write skipped — no org on principal. action={} target={}/{}",
                    action, targetType, targetId);
            return;
        }

        try {
            AuditLog row = new AuditLog();
            row.setOrgId(principal.orgId());
            row.setActorId(principal.userId());
            row.setActorEmail(lookupEmail(principal.userId()));
            row.setAction(action);
            row.setTargetType(targetType);
            row.setTargetId(targetId);
            row.setSummary(truncate(summary));
            // saveAndFlush, not save: with a plain save() the INSERT is only
            // queued and runs at commit — outside this try, where the catch can
            // no longer see it. Flushing here at least brings the remaining
            // failure modes back inside the block that logs them.
            auditLogs.saveAndFlush(row);
        } catch (RuntimeException e) {
            // Never rethrow — audit failures must not break the surrounding business txn.
            log.error("Audit write failed action={} target={} org={} actor={}: {}",
                    action, targetType, principal.orgId(), principal.userId(), e.getMessage(), e);
        }
    }

    /** {@code summary} is {@code VARCHAR(512) NOT NULL} — overlong text is clipped, never rejected. */
    private static String truncate(String summary) {
        if (summary == null) return "";
        return summary.length() <= 512 ? summary : summary.substring(0, 512);
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

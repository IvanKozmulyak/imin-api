package com.imin.iminapi.service.audit;

import com.imin.iminapi.model.AuditLog;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AuditLogRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.imin.iminapi.config.TestRateLimitConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * refund-4: {@link AuditLogger#record} promises that a failed audit write is swallowed.
 * A Mockito-only test cannot check that promise — it is broken by the transaction
 * interceptor, not by the code inside the try — so this boots the real one.
 *
 * <p>Mechanism being guarded: a repository call inside a REQUIRES_NEW transaction marks
 * that transaction rollback-only when it throws, and the interceptor's later commit turns
 * that into an {@code UnexpectedRollbackException} thrown at the CALLER, rolling back a
 * business change that had already succeeded.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class AuditLoggerTransactionTest {

    @Autowired AuditLogger auditLogger;
    @Autowired AuditLogRepository auditLogs;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void a_failing_audit_insert_does_not_roll_back_the_caller_transaction() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal principal =
            new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());

        TransactionTemplate tx = new TransactionTemplate(txManager);
        UUID businessRowId = tx.execute(status -> {
            // Stands in for the organizer mutation the caller is committing.
            AuditLog business = new AuditLog();
            business.setOrgId(orgId);
            business.setAction(AuditActions.EVENT_UPDATED);
            business.setSummary("business change");
            business.setOccurredAt(Instant.now());
            UUID id = auditLogs.save(business).getId();

            // audit_logs.target_type is varchar(32) (V21), so 40 chars cannot be inserted.
            auditLogger.record(principal, AuditActions.EVENT_UPDATED,
                "x".repeat(40), null, "audit write that cannot succeed");
            return id;
        });

        assertThat(auditLogs.findById(businessRowId))
            .as("the caller's transaction must still commit when the audit insert fails")
            .isPresent();
    }
}

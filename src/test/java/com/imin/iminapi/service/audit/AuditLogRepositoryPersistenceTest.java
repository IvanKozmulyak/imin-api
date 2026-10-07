package com.imin.iminapi.service.audit;

import com.imin.iminapi.model.AuditLog;
import com.imin.iminapi.repository.AuditLogRepository;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit-log endpoint's org-scoping query on Postgres: {@code findByOrgIdOrderByOccurredAtDesc}
 * returns only the caller's org, newest first.
 */
@IminIntegrationTest
class AuditLogRepositoryPersistenceTest {

    @Autowired AuditLogRepository auditLogs;

    @Test
    void findByOrgIdOrderByOccurredAtDesc_filtersByOrgAndOrdersNewestFirst() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();

        AuditLog older = persist(orgA, "EVENT_CREATED", Instant.parse("2026-01-01T10:00:00Z"));
        AuditLog newer = persist(orgA, "EVENT_PUBLISHED", Instant.parse("2026-05-01T10:00:00Z"));
        AuditLog otherOrg = persist(orgB, "EVENT_CREATED", Instant.parse("2026-05-01T11:00:00Z"));

        Page<AuditLog> page = auditLogs.findByOrgIdOrderByOccurredAtDesc(orgA, PageRequest.of(0, 20));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getId()).isEqualTo(newer.getId());
        assertThat(page.getContent().get(1).getId()).isEqualTo(older.getId());
        assertThat(page.getContent()).noneMatch(r -> r.getId().equals(otherOrg.getId()));
    }

    private AuditLog persist(UUID orgId, String action, Instant occurredAt) {
        AuditLog r = new AuditLog();
        r.setOrgId(orgId);
        r.setActorId(UUID.randomUUID());
        r.setAction(action);
        r.setSummary("s");
        r.setOccurredAt(occurredAt);
        return auditLogs.save(r);
    }
}

package com.imin.iminapi.service.audit;

import com.imin.iminapi.model.AuditLog;
import com.imin.iminapi.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the H2 schema (including V21) and verifies {@link AuditLog} round-trips
 * via {@link AuditLogRepository}. Also verifies the
 * {@code findByOrgIdOrderByOccurredAtDesc} query filters by org and orders
 * newest-first.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuditLogRepositoryPersistenceTest {

    @Autowired AuditLogRepository auditLogs;

    @Test
    void saveAndLoad_assignsIdAndOccurredAt_whenLeftNull() {
        UUID org = UUID.randomUUID();
        AuditLog row = new AuditLog();
        row.setOrgId(org);
        row.setActorId(UUID.randomUUID());
        row.setActorEmail("alice@example.com");
        row.setAction("EVENT_CREATED");
        row.setTargetType("event");
        row.setTargetId(UUID.randomUUID());
        row.setSummary("Created event \"Test\"");
        // id and occurredAt deliberately left null — @PrePersist must fill them.

        AuditLog saved = auditLogs.save(row);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOccurredAt()).isNotNull();

        Optional<AuditLog> loaded = auditLogs.findById(saved.getId());
        assertThat(loaded).isPresent();
        AuditLog l = loaded.get();
        assertThat(l.getOrgId()).isEqualTo(org);
        assertThat(l.getActorEmail()).isEqualTo("alice@example.com");
        assertThat(l.getAction()).isEqualTo("EVENT_CREATED");
        assertThat(l.getTargetType()).isEqualTo("event");
        assertThat(l.getSummary()).isEqualTo("Created event \"Test\"");
    }

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

package com.imin.iminapi.support;

import com.imin.iminapi.model.AuditLog;
import com.imin.iminapi.repository.AuditLogRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Reads the rows the real AuditLogger committed, so a test asserts the trail instead of a mock call. */
public class AuditRows {

    private final AuditLogRepository auditLogs;

    public AuditRows(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    public List<AuditLog> forOrg(UUID orgId) {
        return auditLogs.findByOrgIdOrderByOccurredAtDesc(orgId, Pageable.unpaged()).getContent();
    }

    /** Exactly one row with this action on this target, returned for further asserts. */
    public AuditLog assertRecorded(UUID orgId, String action, String targetType, UUID targetId) {
        List<AuditLog> rows = forOrg(orgId);
        List<AuditLog> matching = rows.stream()
                .filter(r -> action.equals(r.getAction())
                        && Objects.equals(targetType, r.getTargetType())
                        && Objects.equals(targetId, r.getTargetId()))
                .toList();
        assertThat(matching)
                .as("audit rows %s/%s/%s for org %s; org has %s", action, targetType, targetId, orgId,
                        rows.stream().map(r -> r.getAction() + "/" + r.getTargetType() + "/" + r.getTargetId()).toList())
                .hasSize(1);
        return matching.get(0);
    }
}

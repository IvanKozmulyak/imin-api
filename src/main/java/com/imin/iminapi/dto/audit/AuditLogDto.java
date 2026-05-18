package com.imin.iminapi.dto.audit;

import com.imin.iminapi.model.AuditLog;

import java.time.Instant;
import java.util.UUID;

public record AuditLogDto(UUID id, String action, String targetType, UUID targetId,
                          String summary, String actorEmail, Instant occurredAt) {
    public static AuditLogDto from(AuditLog row) {
        return new AuditLogDto(
                row.getId(),
                row.getAction(),
                row.getTargetType(),
                row.getTargetId(),
                row.getSummary(),
                row.getActorEmail(),
                row.getOccurredAt());
    }
}

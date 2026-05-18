package com.imin.iminapi.service.audit;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.audit.AuditLogDto;
import com.imin.iminapi.repository.AuditLogRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side of the audit log. Always scoped to the caller's organisation —
 * cross-org reads are physically impossible because the repository query
 * filters on {@link AuthPrincipal#orgId()}.
 */
@Service
public class AuditQueryService {

    private final AuditLogRepository auditLogs;

    public AuditQueryService(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogDto> list(AuthPrincipal principal, int page, int pageSize) {
        int clampedPageSize = Math.min(100, Math.max(1, pageSize));
        int clampedPage = Math.max(1, page);
        var pg = PageRequest.of(clampedPage - 1, clampedPageSize);
        var result = auditLogs.findByOrgIdOrderByOccurredAtDesc(principal.orgId(), pg);
        return PageResponse.from(result, AuditLogDto::from);
    }
}

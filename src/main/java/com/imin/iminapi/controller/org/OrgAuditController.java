package com.imin.iminapi.controller.org;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.audit.AuditLogDto;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.audit.AuditQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/org/audit")
public class OrgAuditController {

    private final AuditQueryService auditQueryService;

    public OrgAuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping
    public PageResponse<AuditLogDto> list(@CurrentUser AuthPrincipal p,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int pageSize) {
        return auditQueryService.list(p, page, pageSize);
    }
}

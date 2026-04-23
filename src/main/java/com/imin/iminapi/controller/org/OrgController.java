package com.imin.iminapi.controller.org;

import com.imin.iminapi.dto.OrganizationDto;
import com.imin.iminapi.dto.org.OrgPatchRequest;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.org.OrgService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/org")
public class OrgController {

    private final OrgService orgService;

    public OrgController(OrgService orgService) { this.orgService = orgService; }

    @GetMapping
    public OrganizationDto get(@CurrentUser AuthPrincipal p) { return orgService.get(p); }

    @PatchMapping
    public OrganizationDto patch(@CurrentUser AuthPrincipal p,
                                 @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                 @Valid @RequestBody OrgPatchRequest body) {
        return orgService.patch(p, ifMatch, body);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser AuthPrincipal p) { orgService.delete(p); }
}

package com.imin.iminapi.controller.org;

import com.imin.iminapi.dto.OrganizationDto;
import com.imin.iminapi.dto.auth.MeResponse;
import com.imin.iminapi.dto.org.OrgPatchRequest;
import com.imin.iminapi.dto.org.OrgSetupRequest;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.org.OrgService;
import com.imin.iminapi.service.org.OrgSetupService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/org")
public class OrgController {

    private final OrgService orgService;
    private final OrgSetupService setupService;

    public OrgController(OrgService orgService, OrgSetupService setupService) {
        this.orgService = orgService;
        this.setupService = setupService;
    }

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

    /**
     * Finishes a social sign-up: 200 with the updated user and org; 400 FIELD_INVALID (fields, stale termsVersion,
     * timezone); 403 not the owner; 409 INVALID_STATE already set up; 422 COUNTRY_NOT_SUPPORTED / COUNTRY_NOT_ALLOWED.
     */
    @PostMapping("/setup")
    public MeResponse setup(@CurrentUser AuthPrincipal p, @Valid @RequestBody OrgSetupRequest body) {
        return setupService.complete(p, body);
    }
}

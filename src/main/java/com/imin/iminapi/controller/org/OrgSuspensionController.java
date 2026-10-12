package com.imin.iminapi.controller.org;

import com.imin.iminapi.dto.org.OrgSuspensionResponse;
import com.imin.iminapi.moderation.ModerationHoldService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Any member of the org reads its active moderation holds for the dashboard banner; a gate device gets 404. */
@RestController
public class OrgSuspensionController {

    private final ModerationHoldService service;

    public OrgSuspensionController(ModerationHoldService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/org/suspension")
    public OrgSuspensionResponse suspension(@CurrentUser AuthPrincipal p) {
        return service.activeForOrganizer(p);
    }
}

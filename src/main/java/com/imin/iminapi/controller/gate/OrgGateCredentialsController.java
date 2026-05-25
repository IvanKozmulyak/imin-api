package com.imin.iminapi.controller.gate;

import com.imin.iminapi.dto.gate.GateCredentialStatusResponse;
import com.imin.iminapi.dto.gate.RotateGatePasswordRequest;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.gate.GateAuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Organizer-side endpoints to view and rotate the gate-scanner credential.
 *
 * <p>Both routes are authenticated by the regular user-JWT bearer filter and
 * gated on {@code principal.orgId() == pathOrgId} inside {@link GateAuthService}
 * — a cross-org probe gets the same leak-safe 404 envelope as every other
 * {@code /api/v1/orgs/{orgId}/...} route (see {@code CrossOrgScopingTest}).
 */
@RestController
public class OrgGateCredentialsController {

    private final GateAuthService service;

    public OrgGateCredentialsController(GateAuthService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/orgs/{orgId}/gate/credentials")
    public GateCredentialStatusResponse status(@CurrentUser AuthPrincipal me,
                                                @PathVariable UUID orgId) {
        return service.status(me, orgId);
    }

    @PostMapping("/api/v1/orgs/{orgId}/gate/credentials/rotate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rotate(@CurrentUser AuthPrincipal me,
                       @PathVariable UUID orgId,
                       @Valid @RequestBody RotateGatePasswordRequest body) {
        service.rotate(me, orgId, body.password());
    }
}

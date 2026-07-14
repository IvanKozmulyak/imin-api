package com.imin.iminapi.marketing.controller;

import com.imin.iminapi.marketing.dto.MetaConnectionDto;
import com.imin.iminapi.marketing.dto.MetaStatsDto;
import com.imin.iminapi.marketing.dto.MetaTestEventResult;
import com.imin.iminapi.marketing.dto.PutMetaConnectionRequest;
import com.imin.iminapi.marketing.service.MetaConnectionService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organizer-authenticated Meta Pixel + CAPI connection surface (spec §5).
 * Org is resolved from the auth context — never in the path.
 */
@RestController
@RequestMapping("/api/v1/marketing/meta")
public class MetaConnectionController {

    private final MetaConnectionService service;

    public MetaConnectionController(MetaConnectionService service) {
        this.service = service;
    }

    @GetMapping("/connection")
    public MetaConnectionDto get(@CurrentUser AuthPrincipal principal) {
        return service.get(principal.orgId());
    }

    @PutMapping("/connection")
    public MetaConnectionDto put(@CurrentUser AuthPrincipal principal,
                                 @Valid @RequestBody PutMetaConnectionRequest body) {
        return service.upsert(principal.orgId(), principal.userId(), body);
    }

    @DeleteMapping("/connection")
    public ResponseEntity<Void> delete(@CurrentUser AuthPrincipal principal) {
        service.delete(principal.orgId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/connection/test")
    public MetaTestEventResult test(@CurrentUser AuthPrincipal principal) {
        return service.test(principal.orgId());
    }

    @GetMapping("/stats")
    public MetaStatsDto stats(@CurrentUser AuthPrincipal principal) {
        return service.stats(principal.orgId());
    }
}

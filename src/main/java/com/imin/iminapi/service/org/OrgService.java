package com.imin.iminapi.service.org;

import com.imin.iminapi.dto.OrganizationDto;
import com.imin.iminapi.dto.org.OrgPatchRequest;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.web.IfMatchSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class OrgService {

    private final OrganizationRepository orgs;
    private final IfMatchSupport ifMatch;

    public OrgService(OrganizationRepository orgs, IfMatchSupport ifMatch) {
        this.orgs = orgs;
        this.ifMatch = ifMatch;
    }

    @Transactional(readOnly = true)
    public OrganizationDto get(AuthPrincipal p) {
        Organization o = orgs.findById(p.orgId()).orElseThrow(() -> ApiException.notFound("Organization"));
        return OrganizationDto.from(o);
    }

    @Transactional
    public OrganizationDto patch(AuthPrincipal p, String ifMatchHeader, OrgPatchRequest body) {
        Organization o = orgs.findById(p.orgId()).orElseThrow(() -> ApiException.notFound("Organization"));
        ifMatch.requireMatch(ifMatchHeader, o.getUpdatedAt());
        if (body.name() != null) o.setName(body.name());
        if (body.contactEmail() != null) o.setContactEmail(body.contactEmail());
        if (body.country() != null) o.setCountry(body.country().toUpperCase());
        if (body.timezone() != null) o.setTimezone(body.timezone());
        o.setUpdatedAt(Instant.now());
        return OrganizationDto.from(orgs.save(o));
    }

    @Transactional
    public void delete(AuthPrincipal p) {
        if (p.role() != UserRole.OWNER) throw ApiException.forbidden("Only the org owner can delete the organization");
        if (!orgs.existsById(p.orgId())) throw ApiException.notFound("Organization");
        orgs.deleteById(p.orgId()); // FK ON DELETE CASCADE wipes users, events, etc.
    }
}

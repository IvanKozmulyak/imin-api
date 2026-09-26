package com.imin.iminapi.audienceplan.config;

import com.imin.iminapi.security.ApiException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
public class AudiencePlanAccess {

    private final AudiencePlanProperties props;

    public AudiencePlanAccess(AudiencePlanProperties props) {
        this.props = props;
    }

    /**
     * Call first, before any org or event lookup, so a disabled feature and a missing event
     * return the same 404.
     */
    public void requireEnabled(UUID orgId) {
        Set<UUID> allowed = props.getBetaOrgIds();
        if (!props.isEnabled() || orgId == null || (!allowed.isEmpty() && !allowed.contains(orgId))) {
            throw ApiException.notFound("Audience plan");
        }
    }
}

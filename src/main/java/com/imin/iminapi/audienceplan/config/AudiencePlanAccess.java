package com.imin.iminapi.audienceplan.config;

import com.imin.iminapi.security.ApiException;
import org.springframework.stereotype.Component;

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
        if (!props.isEnabled() || orgId == null || !props.getBetaOrgIds().contains(orgId)) {
            throw ApiException.notFound("Audience plan");
        }
    }
}

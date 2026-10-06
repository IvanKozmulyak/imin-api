package com.imin.iminapi.predictor.config;

import com.imin.iminapi.security.ApiException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class DateCheckAccess {

    private final DateCheckProperties props;

    public DateCheckAccess(DateCheckProperties props) {
        this.props = props;
    }

    /** True when the feature is on and the org is let through (all orgs, or listed in the beta). */
    public boolean isEnabled(UUID orgId) {
        return Boolean.TRUE.equals(props.getEnabled()) && orgId != null
                && (Boolean.TRUE.equals(props.getAllOrgs()) || props.getBetaOrgIds().contains(orgId));
    }

    /** Web research for this org: the gate above, the research flag, and the org on the research list. */
    public boolean isResearchEnabled(UUID orgId) {
        return isEnabled(orgId) && Boolean.TRUE.equals(props.getResearchEnabled())
                && props.getResearchOrgIds().contains(orgId);
    }

    /** Call first, before any org or event lookup, so a closed gate and a missing resource return the same 404. */
    public void requireEnabled(UUID orgId) {
        if (!isEnabled(orgId)) {
            throw ApiException.notFound("Date check");
        }
    }
}

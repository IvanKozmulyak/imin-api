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

    /** Call first, before any org or event lookup, so a closed gate and a missing resource return the same 404. */
    public void requireEnabled(UUID orgId) {
        if (!Boolean.TRUE.equals(props.getEnabled()) || orgId == null
                || !(Boolean.TRUE.equals(props.getAllOrgs()) || props.getBetaOrgIds().contains(orgId))) {
            throw ApiException.notFound("Date check");
        }
    }
}

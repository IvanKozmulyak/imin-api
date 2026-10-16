package com.imin.iminapi.service.org;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 409 SETUP_REQUIRED on Stripe Connect and publishing until the owner of a social sign-up finishes setup. */
@Component
public class OrgSetupGuard {

    private final OrganizationRepository orgs;

    public OrgSetupGuard(OrganizationRepository orgs) {
        this.orgs = orgs;
    }

    /** 409 {@code SETUP_REQUIRED} while the org's setup is pending. */
    public void requireCompleteById(UUID orgId) {
        if (orgs.existsByIdAndSetupCompletedAtIsNull(orgId)) throw setupRequired();
    }

    /** Same check on an already-loaded org. */
    public static void requireComplete(Organization org) {
        if (org.getSetupCompletedAt() == null) throw setupRequired();
    }

    private static ApiException setupRequired() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.SETUP_REQUIRED,
                "Finish setting up your organization first");
    }
}

package com.imin.iminapi.audienceplan.config;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
public class AudiencePlanAccess {

    /** {@code campaigns.origin} of a campaign created by the audience plan tool. */
    public static final String CAMPAIGN_ORIGIN = "audience_plan";

    private final AudiencePlanProperties props;

    public AudiencePlanAccess(AudiencePlanProperties props) {
        this.props = props;
    }

    /**
     * Call first, before any org or event lookup, so a disabled feature and a missing event
     * return the same 404.
     */
    public void requireEnabled(UUID orgId) {
        if (!isEnabled(orgId)) {
            throw ApiException.notFound("Audience plan");
        }
    }

    /** Non-throwing form for background work: false for a null org, the kill switch, or an org off a non-blank list. */
    public boolean isEnabled(UUID orgId) {
        Set<UUID> allowed = props.getBetaOrgIds();
        return props.isEnabled() && orgId != null && (allowed.isEmpty() || allowed.contains(orgId));
    }

    /** The sends switch for audience-plan campaigns; other origins ignore it. */
    public boolean sendsEnabled() {
        return Boolean.TRUE.equals(props.getSendsEnabled());
    }

    /** Refuses to schedule or send an audience-plan campaign while its sends switch is off. */
    public void requireSendsAllowed(String campaignOrigin) {
        if (!sendsEnabled() && CAMPAIGN_ORIGIN.equals(campaignOrigin)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.AUDIENCE_SENDS_DISABLED,
                    "Sending audience plan campaigns is not enabled yet");
        }
    }

    /** True while every campaign, whatever its origin, needs the org's legal name and contact. */
    public boolean legalIdentityAllCampaigns() {
        return Boolean.TRUE.equals(props.getLegalIdentityAllCampaigns());
    }

    /** Audience-plan campaigns always need the legal identity; other origins only while the all-campaigns flag is on. */
    public boolean legalIdentityRequired(String campaignOrigin) {
        return CAMPAIGN_ORIGIN.equals(campaignOrigin) || legalIdentityAllCampaigns();
    }

    /** Refuses a campaign that needs the org's legal name and contact for the email footer when either is missing. */
    public void requireLegalIdentity(String campaignOrigin, Organization org) {
        if (legalIdentityRequired(campaignOrigin) && (org == null || !org.hasLegalIdentity())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ORG_LEGAL_IDENTITY_MISSING,
                    "Add the organization's legal name and legal contact before scheduling this campaign");
        }
    }
}

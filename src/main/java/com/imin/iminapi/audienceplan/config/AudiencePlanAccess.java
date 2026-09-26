package com.imin.iminapi.audienceplan.config;

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
}

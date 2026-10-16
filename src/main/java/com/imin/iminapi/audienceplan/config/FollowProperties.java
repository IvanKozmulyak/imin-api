package com.imin.iminapi.audienceplan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * The imin.wtf "Keep me posted" sign-up ({@code POST /api/v1/public/orgs/{slug}/follow}). Only orgs whose slug is
 * listed take sign-ups (empty = every slug 404s); only the exact {@code allowedOrigins} get CORS on that path.
 */
@ConfigurationProperties(prefix = "imin.follow")
public class FollowProperties {

    private List<String> orgSlugs = List.of();

    private List<String> allowedOrigins = List.of("https://imin.wtf");

    public List<String> getOrgSlugs() { return orgSlugs; }
    public void setOrgSlugs(List<String> orgSlugs) { this.orgSlugs = clean(orgSlugs); }

    public List<String> getAllowedOrigins() { return allowedOrigins; }

    /** Exact origins only: a wildcard would open the follow path to sites that are not imin.wtf, so it stops startup. */
    public void setAllowedOrigins(List<String> allowedOrigins) {
        List<String> origins = clean(allowedOrigins);
        for (String origin : origins) {
            if (origin.contains("*")) {
                throw new IllegalArgumentException("imin.follow.allowed-origins takes exact origins, not '" + origin + "'");
            }
        }
        this.allowedOrigins = origins;
    }

    /** Trimmed, blank entries dropped: an unset variable binds to one empty string. */
    private static List<String> clean(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList();
    }
}

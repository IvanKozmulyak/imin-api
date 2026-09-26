package com.imin.iminapi.audienceplan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Beta gate for the audience plan tool: only listed orgs pass, and only while enabled.
 * A blank org list means nobody.
 */
@ConfigurationProperties(prefix = "imin.audience-plan")
public class AudiencePlanProperties {

    private boolean enabled = false;

    private Set<UUID> betaOrgIds = Set.of();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Set<UUID> getBetaOrgIds() { return betaOrgIds; }

    /** Blank elements (e.g. a trailing comma) convert to null; drop them rather than fail. */
    public void setBetaOrgIds(Set<UUID> betaOrgIds) {
        if (betaOrgIds == null) {
            this.betaOrgIds = Set.of();
            return;
        }
        Set<UUID> cleaned = new HashSet<>();
        for (UUID id : betaOrgIds) {
            if (id != null) {
                cleaned.add(id);
            }
        }
        this.betaOrgIds = Set.copyOf(cleaned);
    }
}

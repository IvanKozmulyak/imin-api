package com.imin.iminapi.audienceplan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Global kill switch for the audience plan tool, open by default.
 * A non-blank org list restricts access to those orgs; a blank list means all orgs.
 */
@ConfigurationProperties(prefix = "imin.audience-plan")
public class AudiencePlanProperties {

    private boolean enabled = true;

    private Set<UUID> betaOrgIds = Set.of();

    private String logicFile = "classpath:audienceplan/logic-v1.yaml";

    private String priorsFile = "classpath:audienceplan/priors-v1.yaml";

    private String genresFile = "classpath:audienceplan/genres-v1.yaml";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Set<UUID> getBetaOrgIds() { return betaOrgIds; }
    public String getLogicFile() { return logicFile; }
    public void setLogicFile(String logicFile) { this.logicFile = logicFile; }
    public String getPriorsFile() { return priorsFile; }
    public void setPriorsFile(String priorsFile) { this.priorsFile = priorsFile; }
    public String getGenresFile() { return genresFile; }
    public void setGenresFile(String genresFile) { this.genresFile = genresFile; }

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

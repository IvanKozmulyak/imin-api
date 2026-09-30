package com.imin.iminapi.predictor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Gate for "Check a date". Dark by default; an empty org list means no org, unlike the
 * audience plan gate.
 */
@ConfigurationProperties(prefix = "imin.predictor.date-check")
public class DateCheckProperties {

    static final int DEFAULT_MAX_DATES = 5;
    static final int DEFAULT_MAX_HORIZON_MONTHS = 18;

    private Boolean enabled = Boolean.FALSE;

    private Set<UUID> betaOrgIds = Set.of();

    /** Cite-only web research; off until the provider spike and legal review pass. */
    private Boolean researchEnabled = Boolean.FALSE;

    private Integer maxDates = DEFAULT_MAX_DATES;

    private Integer maxHorizonMonths = DEFAULT_MAX_HORIZON_MONTHS;

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean v) { this.enabled = Boolean.TRUE.equals(v); }
    public Set<UUID> getBetaOrgIds() { return betaOrgIds; }
    public Boolean getResearchEnabled() { return researchEnabled; }
    public void setResearchEnabled(Boolean v) { this.researchEnabled = Boolean.TRUE.equals(v); }
    public Integer getMaxDates() { return maxDates; }
    public void setMaxDates(Integer v) { this.maxDates = v == null || v < 1 ? DEFAULT_MAX_DATES : v; }
    public Integer getMaxHorizonMonths() { return maxHorizonMonths; }
    public void setMaxHorizonMonths(Integer v) { this.maxHorizonMonths = v == null || v < 1 ? DEFAULT_MAX_HORIZON_MONTHS : v; }

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

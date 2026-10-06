package com.imin.iminapi.predictor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
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
    static final int DEFAULT_RESEARCH_CAP_PER_ORG = 10;
    static final int DEFAULT_RESEARCH_CAP_GLOBAL = 100;
    static final String DEFAULT_RESEARCH_MODEL = "anthropic/claude-haiku-4.5";
    static final Duration DEFAULT_RESEARCH_TIMEOUT = Duration.ofSeconds(30);
    /** {@code prediction_ledger.model_id} is VARCHAR(128). */
    static final int MAX_MODEL_LENGTH = 128;

    private Boolean enabled = Boolean.FALSE;

    private Set<UUID> betaOrgIds = Set.of();

    /** Opens the gate to every org while enabled; the beta list is then ignored. */
    private Boolean allOrgs = Boolean.FALSE;

    /** Cite-only web research; also needs the org in {@link #researchOrgIds}. */
    private Boolean researchEnabled = Boolean.FALSE;

    /** Orgs that may run web research; empty means no org, whatever the other flags say. */
    private Set<UUID> researchOrgIds = Set.of();

    /** Research runs queued per org and across all orgs per UTC day; below 1 binds the default. */
    private Integer researchDailyCapPerOrg = DEFAULT_RESEARCH_CAP_PER_ORG;
    private Integer researchDailyCapGlobal = DEFAULT_RESEARCH_CAP_GLOBAL;

    /** OpenRouter model for the research call; blank binds the default. */
    private String researchModel = DEFAULT_RESEARCH_MODEL;

    /** Longest wait for the research answer; no retry after it. */
    private Duration researchTimeout = DEFAULT_RESEARCH_TIMEOUT;

    /** Daily radar re-run of each live event's date check at D-30/14/7/2; also needs {@code enabled}. */
    private Boolean radarEnabled = Boolean.FALSE;

    private Integer maxDates = DEFAULT_MAX_DATES;

    private Integer maxHorizonMonths = DEFAULT_MAX_HORIZON_MONTHS;

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean v) { this.enabled = Boolean.TRUE.equals(v); }
    public Set<UUID> getBetaOrgIds() { return betaOrgIds; }
    public Boolean getAllOrgs() { return allOrgs; }
    public void setAllOrgs(Boolean v) { this.allOrgs = Boolean.TRUE.equals(v); }
    public Boolean getResearchEnabled() { return researchEnabled; }
    public void setResearchEnabled(Boolean v) { this.researchEnabled = Boolean.TRUE.equals(v); }
    public Boolean getRadarEnabled() { return radarEnabled; }
    public void setRadarEnabled(Boolean v) { this.radarEnabled = Boolean.TRUE.equals(v); }
    public Integer getMaxDates() { return maxDates; }
    public void setMaxDates(Integer v) { this.maxDates = v == null || v < 1 ? DEFAULT_MAX_DATES : v; }
    public Integer getMaxHorizonMonths() { return maxHorizonMonths; }
    public void setMaxHorizonMonths(Integer v) { this.maxHorizonMonths = v == null || v < 1 ? DEFAULT_MAX_HORIZON_MONTHS : v; }

    public Set<UUID> getResearchOrgIds() { return researchOrgIds; }
    public void setResearchOrgIds(Set<UUID> v) { this.researchOrgIds = withoutNulls(v); }
    public Integer getResearchDailyCapPerOrg() { return researchDailyCapPerOrg; }
    public void setResearchDailyCapPerOrg(Integer v) { this.researchDailyCapPerOrg = v == null || v < 1 ? DEFAULT_RESEARCH_CAP_PER_ORG : v; }
    public Integer getResearchDailyCapGlobal() { return researchDailyCapGlobal; }
    public void setResearchDailyCapGlobal(Integer v) { this.researchDailyCapGlobal = v == null || v < 1 ? DEFAULT_RESEARCH_CAP_GLOBAL : v; }
    public String getResearchModel() { return researchModel; }
    public Duration getResearchTimeout() { return researchTimeout; }
    public void setResearchTimeout(Duration v) { this.researchTimeout = v == null || v.isNegative() || v.isZero() ? DEFAULT_RESEARCH_TIMEOUT : v; }

    public void setResearchModel(String v) {
        String m = v == null || v.isBlank() ? DEFAULT_RESEARCH_MODEL : v.trim();
        if (m.length() > MAX_MODEL_LENGTH) {
            throw new IllegalArgumentException("research-model is longer than " + MAX_MODEL_LENGTH + " characters");
        }
        this.researchModel = m;
    }

    /** Blank elements (e.g. a trailing comma) convert to null; drop them rather than fail. */
    public void setBetaOrgIds(Set<UUID> betaOrgIds) {
        this.betaOrgIds = withoutNulls(betaOrgIds);
    }

    private static Set<UUID> withoutNulls(Set<UUID> ids) {
        if (ids == null) return Set.of();
        Set<UUID> cleaned = new HashSet<>();
        for (UUID id : ids) {
            if (id != null) cleaned.add(id);
        }
        return Set.copyOf(cleaned);
    }
}

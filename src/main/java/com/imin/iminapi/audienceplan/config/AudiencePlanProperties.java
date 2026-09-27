package com.imin.iminapi.audienceplan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
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

    /** Real sends of audience-plan campaigns; stays false until the platform legal gates clear. Blank binds false. */
    private Boolean sendsEnabled = Boolean.FALSE;

    /** Lets ConsentGate count a soft opt-in grounded on a paid order; explicit consent only while false. Blank binds false. */
    private Boolean softOptInEnabled = Boolean.FALSE;

    /** Lets the nightly retention job remove basis past 3 years; false = dry run that only logs counts. Blank binds false. */
    private Boolean retentionJobEnabled = Boolean.FALSE;

    /** Requires ConsentGate for every campaign's recipients, not only audience-plan ones. Blank binds false. */
    private Boolean consentGateAllCampaigns = Boolean.FALSE;

    /** OpenRouter model for plan summaries; blank uses {@code openrouter.model}. */
    private String summaryModel = "";

    /** Lazy LLM summaries on the plan GET; false keeps {@code summary} null. Blank binds true. */
    private Boolean summaryEnabled = Boolean.TRUE;

    /** USD per million prompt / completion tokens of the summary model; either blank leaves cost_usd null. */
    private BigDecimal summaryPriceInputUsdPerMtok;
    private BigDecimal summaryPriceOutputUsdPerMtok;

    /** Model summary calls per org per UTC day, retries included; past it the template is used. Blank binds 50. */
    private Integer summaryDailyCapPerOrg = DEFAULT_SUMMARY_DAILY_CAP;

    /** Longest wait for one summary answer; the call is abandoned and the template used. Blank binds 30s. */
    private Duration summaryTimeout = DEFAULT_SUMMARY_TIMEOUT;

    static final int DEFAULT_SUMMARY_DAILY_CAP = 50;
    static final Duration DEFAULT_SUMMARY_TIMEOUT = Duration.ofSeconds(30);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Set<UUID> getBetaOrgIds() { return betaOrgIds; }
    public String getLogicFile() { return logicFile; }
    public void setLogicFile(String logicFile) { this.logicFile = logicFile; }
    public String getPriorsFile() { return priorsFile; }
    public void setPriorsFile(String priorsFile) { this.priorsFile = priorsFile; }
    public String getGenresFile() { return genresFile; }
    public void setGenresFile(String genresFile) { this.genresFile = genresFile; }
    public Boolean getSendsEnabled() { return sendsEnabled; }
    public void setSendsEnabled(Boolean sendsEnabled) { this.sendsEnabled = Boolean.TRUE.equals(sendsEnabled); }
    public Boolean getSoftOptInEnabled() { return softOptInEnabled; }
    public void setSoftOptInEnabled(Boolean softOptInEnabled) { this.softOptInEnabled = Boolean.TRUE.equals(softOptInEnabled); }
    public Boolean getRetentionJobEnabled() { return retentionJobEnabled; }
    public void setRetentionJobEnabled(Boolean retentionJobEnabled) { this.retentionJobEnabled = Boolean.TRUE.equals(retentionJobEnabled); }
    public Boolean getConsentGateAllCampaigns() { return consentGateAllCampaigns; }
    public void setConsentGateAllCampaigns(Boolean consentGateAllCampaigns) { this.consentGateAllCampaigns = Boolean.TRUE.equals(consentGateAllCampaigns); }

    public String getSummaryModel() { return summaryModel; }
    public void setSummaryModel(String summaryModel) { this.summaryModel = summaryModel == null ? "" : summaryModel.trim(); }
    public Boolean getSummaryEnabled() { return summaryEnabled; }
    public void setSummaryEnabled(Boolean summaryEnabled) { this.summaryEnabled = !Boolean.FALSE.equals(summaryEnabled); }
    public BigDecimal getSummaryPriceInputUsdPerMtok() { return summaryPriceInputUsdPerMtok; }
    public void setSummaryPriceInputUsdPerMtok(BigDecimal v) { this.summaryPriceInputUsdPerMtok = v; }
    public BigDecimal getSummaryPriceOutputUsdPerMtok() { return summaryPriceOutputUsdPerMtok; }
    public void setSummaryPriceOutputUsdPerMtok(BigDecimal v) { this.summaryPriceOutputUsdPerMtok = v; }
    public Integer getSummaryDailyCapPerOrg() { return summaryDailyCapPerOrg; }
    public void setSummaryDailyCapPerOrg(Integer v) { this.summaryDailyCapPerOrg = v == null || v < 0 ? DEFAULT_SUMMARY_DAILY_CAP : v; }
    public Duration getSummaryTimeout() { return summaryTimeout; }
    public void setSummaryTimeout(Duration v) { this.summaryTimeout = v == null || v.isNegative() || v.isZero() ? DEFAULT_SUMMARY_TIMEOUT : v; }

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

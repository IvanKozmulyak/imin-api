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

    /** OpenRouter model for plan summaries. Blank binds {@link #DEFAULT_SUMMARY_MODEL}. */
    private String summaryModel = DEFAULT_SUMMARY_MODEL;

    static final String DEFAULT_SUMMARY_MODEL = "anthropic/claude-haiku-4.5";
    // OpenRouter list prices of the default model, checked 2026-09-28; env vars override when they move.
    static final BigDecimal DEFAULT_SUMMARY_PRICE_INPUT = new BigDecimal("1");
    static final BigDecimal DEFAULT_SUMMARY_PRICE_OUTPUT = new BigDecimal("5");

    /** Lazy LLM summaries on the plan GET; false keeps {@code summary} null. Blank binds true. */
    private Boolean summaryEnabled = Boolean.TRUE;

    /**
     * USD per million prompt / completion tokens of the summary model. Blank takes the default model's price only
     * while the model is the default one; with another model a blank price stays null (cost_usd not recorded).
     */
    private BigDecimal summaryPriceInputUsdPerMtok;
    private BigDecimal summaryPriceOutputUsdPerMtok;

    /** Model summary calls per org per UTC day, retries included; past it the template is used. Blank binds 50. */
    private Integer summaryDailyCapPerOrg = DEFAULT_SUMMARY_DAILY_CAP;

    /** Longest wait for one summary answer; the call is abandoned and the template used. Blank binds 30s. */
    private Duration summaryTimeout = DEFAULT_SUMMARY_TIMEOUT;

    static final int DEFAULT_SUMMARY_DAILY_CAP = 50;
    static final Duration DEFAULT_SUMMARY_TIMEOUT = Duration.ofSeconds(30);

    /** Requires the org's legal name and contact for every campaign, not only audience-plan ones. Blank binds false. */
    private Boolean legalIdentityAllCampaigns = Boolean.FALSE;

    /** OpenRouter model for portrait research (web plugin) and extraction; a trailing ":online" is stripped. */
    private String portraitModel = DEFAULT_PORTRAIT_MODEL;

    /** USD per million prompt / completion tokens of the portrait model, used when OpenRouter reports no cost. */
    private BigDecimal portraitPriceInputUsdPerMtok = DEFAULT_PORTRAIT_PRICE_IN;
    private BigDecimal portraitPriceOutputUsdPerMtok = DEFAULT_PORTRAIT_PRICE_OUT;

    /** Longest wait for one portrait answer; no retry after a timeout. Blank binds 30s. */
    private Duration portraitTimeout = DEFAULT_SUMMARY_TIMEOUT;

    /** Portrait LLM calls per org per UTC day, and across all callers (refresh job included). */
    private Integer portraitDailyCapPerOrg = DEFAULT_PORTRAIT_CAP_PER_ORG;
    private Integer portraitDailyCapGlobal = DEFAULT_PORTRAIT_CAP_GLOBAL;

    /** Portraits the weekly refresh renews per run. */
    private Integer portraitRefreshBatch = DEFAULT_PORTRAIT_REFRESH_BATCH;

    static final String DEFAULT_PORTRAIT_MODEL = "anthropic/claude-haiku-4.5";
    static final BigDecimal DEFAULT_PORTRAIT_PRICE_IN = BigDecimal.ONE;
    static final BigDecimal DEFAULT_PORTRAIT_PRICE_OUT = BigDecimal.valueOf(5);
    static final int DEFAULT_PORTRAIT_CAP_PER_ORG = 10;
    static final int DEFAULT_PORTRAIT_CAP_GLOBAL = 100;
    static final int DEFAULT_PORTRAIT_REFRESH_BATCH = 20;

    /** Emails the confirmation link for door QR / survey sign-ups; false leaves them pending. Blank binds false. */
    private Boolean consentConfirmationEmailsEnabled = Boolean.FALSE;

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
    public Boolean getLegalIdentityAllCampaigns() { return legalIdentityAllCampaigns; }
    public void setLegalIdentityAllCampaigns(Boolean legalIdentityAllCampaigns) { this.legalIdentityAllCampaigns = Boolean.TRUE.equals(legalIdentityAllCampaigns); }

    public Boolean getConsentConfirmationEmailsEnabled() { return consentConfirmationEmailsEnabled; }
    public void setConsentConfirmationEmailsEnabled(Boolean v) { this.consentConfirmationEmailsEnabled = Boolean.TRUE.equals(v); }

    public String getSummaryModel() { return summaryModel; }
    public void setSummaryModel(String summaryModel) {
        this.summaryModel = summaryModel == null || summaryModel.isBlank() ? DEFAULT_SUMMARY_MODEL : summaryModel.trim();
    }
    public Boolean getSummaryEnabled() { return summaryEnabled; }
    public void setSummaryEnabled(Boolean summaryEnabled) { this.summaryEnabled = !Boolean.FALSE.equals(summaryEnabled); }
    public BigDecimal getSummaryPriceInputUsdPerMtok() { return price(summaryPriceInputUsdPerMtok, DEFAULT_SUMMARY_PRICE_INPUT); }
    public void setSummaryPriceInputUsdPerMtok(BigDecimal v) { this.summaryPriceInputUsdPerMtok = v; }
    public BigDecimal getSummaryPriceOutputUsdPerMtok() { return price(summaryPriceOutputUsdPerMtok, DEFAULT_SUMMARY_PRICE_OUTPUT); }
    public void setSummaryPriceOutputUsdPerMtok(BigDecimal v) { this.summaryPriceOutputUsdPerMtok = v; }
    private BigDecimal price(BigDecimal configured, BigDecimal defaultModelPrice) {
        if (configured != null) return configured;
        return DEFAULT_SUMMARY_MODEL.equals(summaryModel) ? defaultModelPrice : null;
    }
    public Integer getSummaryDailyCapPerOrg() { return summaryDailyCapPerOrg; }
    public void setSummaryDailyCapPerOrg(Integer v) { this.summaryDailyCapPerOrg = v == null || v < 0 ? DEFAULT_SUMMARY_DAILY_CAP : v; }
    public Duration getSummaryTimeout() { return summaryTimeout; }
    public void setSummaryTimeout(Duration v) { this.summaryTimeout = v == null || v.isNegative() || v.isZero() ? DEFAULT_SUMMARY_TIMEOUT : v; }

    public String getPortraitModel() { return portraitModel; }
    public void setPortraitModel(String v) { this.portraitModel = v == null || v.isBlank() ? DEFAULT_PORTRAIT_MODEL : v.trim(); }
    public BigDecimal getPortraitPriceInputUsdPerMtok() { return portraitPriceInputUsdPerMtok; }
    public void setPortraitPriceInputUsdPerMtok(BigDecimal v) { this.portraitPriceInputUsdPerMtok = v == null ? DEFAULT_PORTRAIT_PRICE_IN : v; }
    public BigDecimal getPortraitPriceOutputUsdPerMtok() { return portraitPriceOutputUsdPerMtok; }
    public void setPortraitPriceOutputUsdPerMtok(BigDecimal v) { this.portraitPriceOutputUsdPerMtok = v == null ? DEFAULT_PORTRAIT_PRICE_OUT : v; }
    public Duration getPortraitTimeout() { return portraitTimeout; }
    public void setPortraitTimeout(Duration v) { this.portraitTimeout = v == null || v.isNegative() || v.isZero() ? DEFAULT_SUMMARY_TIMEOUT : v; }
    public Integer getPortraitDailyCapPerOrg() { return portraitDailyCapPerOrg; }
    public void setPortraitDailyCapPerOrg(Integer v) { this.portraitDailyCapPerOrg = v == null || v < 0 ? DEFAULT_PORTRAIT_CAP_PER_ORG : v; }
    public Integer getPortraitDailyCapGlobal() { return portraitDailyCapGlobal; }
    public void setPortraitDailyCapGlobal(Integer v) { this.portraitDailyCapGlobal = v == null || v < 0 ? DEFAULT_PORTRAIT_CAP_GLOBAL : v; }
    public Integer getPortraitRefreshBatch() { return portraitRefreshBatch; }
    public void setPortraitRefreshBatch(Integer v) { this.portraitRefreshBatch = v == null || v < 1 ? DEFAULT_PORTRAIT_REFRESH_BATCH : v; }

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

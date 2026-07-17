package com.imin.iminapi.marketing.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per-org volume guardrails (spec §7). */
@ConfigurationProperties(prefix = "imin.marketing.guard")
public class MarketingGuardProperties {
    private int dailyCap = 10000;         // max sends per org per rolling 24h (spec §8/§10)
    private int frequencyFloorHours = 48; // a member hit within this window is skipped (spec: 2 days)

    public int getDailyCap() { return dailyCap; }
    public void setDailyCap(int dailyCap) { this.dailyCap = dailyCap; }
    public int getFrequencyFloorHours() { return frequencyFloorHours; }
    public void setFrequencyFloorHours(int frequencyFloorHours) { this.frequencyFloorHours = frequencyFloorHours; }
}

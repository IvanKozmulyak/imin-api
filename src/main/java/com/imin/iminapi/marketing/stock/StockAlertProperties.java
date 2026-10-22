package com.imin.iminapi.marketing.stock;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** {@code imin.marketing.stock-alerts.*}: the platform switch and the timing rules of stock alerts. */
@Component
@ConfigurationProperties(prefix = "imin.marketing.stock-alerts")
public class StockAlertProperties {

    /** Off: the evaluator prepares nothing and the settings, review and dismiss endpoints answer 404. */
    private boolean enabled = false;
    /** Pause between two evaluator passes. */
    private long evaluatorDelayMs = 300_000L;
    /** An automatic alert is scheduled at least this far ahead, so the organizer can still stop it. */
    private int autoMinLeadMinutes = 30;
    /** An automatic "price goes up" alert must be scheduled at least this long before the change. */
    private int priceUpMinMarginHours = 2;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getEvaluatorDelayMs() { return evaluatorDelayMs; }
    public void setEvaluatorDelayMs(long evaluatorDelayMs) { this.evaluatorDelayMs = evaluatorDelayMs; }
    public int getAutoMinLeadMinutes() { return autoMinLeadMinutes; }
    public void setAutoMinLeadMinutes(int autoMinLeadMinutes) { this.autoMinLeadMinutes = autoMinLeadMinutes; }
    public int getPriceUpMinMarginHours() { return priceUpMinMarginHours; }
    public void setPriceUpMinMarginHours(int priceUpMinMarginHours) { this.priceUpMinMarginHours = priceUpMinMarginHours; }
}

package com.imin.iminapi.checkoutreminder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The unfinished-checkout reminder ({@code imin.checkout-reminder.*}). Every default here matches
 * {@code application.yaml}; {@link #enabled} stays false until the privacy text describing the
 * reminder is live, because with it off nothing is captured and nothing is sent.
 */
@ConfigurationProperties(prefix = "imin.checkout-reminder")
public class CheckoutReminderProperties {

    /** Platform switch: off = no row is captured and the sender sends nothing. Empty or unset = off. */
    private Boolean enabled = false;
    /** Minutes after the checkout started before the reminder is due (then moved out of quiet hours). */
    private int delayMinutes = 60;
    /** No reminder when the night starts sooner than this. */
    private int minLeadHours = 3;
    /** No reminder once the checkout started longer ago than this. */
    private int maxAgeHours = 24;
    /** "Only N left" is shown below this many tickets, the same rule as the buy page. */
    private int lowStockBelow = 10;
    /** An order for the night this many days after the send counts as bought after it. */
    private int outcomeWindowDays = 7;
    /** A sent row keeps the address and the ticked sentence this many days, as proof of the tick. */
    private int keepSentDays = 365;
    /** An unsent row loses the address the day after the night, and never later than this. */
    private int maxKeepUnsentDays = 180;

    public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public int getDelayMinutes() { return delayMinutes; }
    public void setDelayMinutes(int delayMinutes) { this.delayMinutes = delayMinutes; }
    public int getMinLeadHours() { return minLeadHours; }
    public void setMinLeadHours(int minLeadHours) { this.minLeadHours = minLeadHours; }
    public int getMaxAgeHours() { return maxAgeHours; }
    public void setMaxAgeHours(int maxAgeHours) { this.maxAgeHours = maxAgeHours; }
    public int getLowStockBelow() { return lowStockBelow; }
    public void setLowStockBelow(int lowStockBelow) { this.lowStockBelow = lowStockBelow; }
    public int getOutcomeWindowDays() { return outcomeWindowDays; }
    public void setOutcomeWindowDays(int outcomeWindowDays) { this.outcomeWindowDays = outcomeWindowDays; }
    public int getKeepSentDays() { return keepSentDays; }
    public void setKeepSentDays(int keepSentDays) { this.keepSentDays = keepSentDays; }
    public int getMaxKeepUnsentDays() { return maxKeepUnsentDays; }
    public void setMaxKeepUnsentDays(int maxKeepUnsentDays) { this.maxKeepUnsentDays = maxKeepUnsentDays; }

    public Duration delay() { return Duration.ofMinutes(delayMinutes); }
    public Duration minLead() { return Duration.ofHours(minLeadHours); }
    public Duration maxAge() { return Duration.ofHours(maxAgeHours); }
    public Duration outcomeWindow() { return Duration.ofDays(outcomeWindowDays); }
}

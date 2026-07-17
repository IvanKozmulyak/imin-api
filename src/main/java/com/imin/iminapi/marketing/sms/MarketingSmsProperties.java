package com.imin.iminapi.marketing.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * INTENDED SMS channel policy for the first SMS release (spec §8/§10).
 *
 * <p><b>Read this before trusting any value here.</b> imin does not currently send SMS.
 * {@code "sms"} is an accepted {@code campaigns.channel} value, but {@code CampaignRepository.claimDue}
 * filters {@code WHERE channel='email'} and no SMS provider, sender or client exists in the tree —
 * so nothing below is enforced by a live dispatcher. These are fixed product decisions surfaced so
 * the Channels tab can show the <i>planned</i> SMS policy WITHOUT implying it is active. The channels
 * DTO reports {@code sms.sendingEnabled=false} and marks the send window {@code enforced=false} so a
 * reader can never mistake these for live guardrails.
 *
 * <p>They live in a {@code @ConfigurationProperties} class (a real, overridable config source) rather
 * than scattered literals, so when the dispatcher does gain an SMS path it can read the SAME values
 * this surface displays — the honest analogue of the email guardrails, which already do exactly that.
 */
@ConfigurationProperties(prefix = "imin.marketing.sms")
public class MarketingSmsProperties {

    /** Alphanumeric sender ID shown as the SMS "from" (e.g. {@code IMIN}). */
    private String senderId = "IMIN";

    /** Downstream SMS aggregator planned for the first release. */
    private String provider = "Bird";

    /** Primary launch region as an E.164 country calling code (e.g. {@code +380}). */
    private String region = "+380";

    /** First-release safety cap: max recipients per SMS campaign. */
    private int firstReleaseRecipientCap = 200;

    /**
     * Start of the intended org-local SMS send window, inclusive. SMS is intended to send only
     * within {@code [sendWindowStartLocal, sendWindowEndLocal)}; outside it is quiet. Note this is
     * an ALLOWED window (09:00–20:00), the inverse framing of email's BLOCKED quiet window
     * (22:00–09:00) — the two channels express the constraint from opposite ends.
     */
    private String sendWindowStartLocal = "09:00";

    /** End of the intended org-local SMS send window, exclusive. */
    private String sendWindowEndLocal = "20:00";

    /** Opted-in phones an org is intended to reach before the SMS channel is unlocked for it. */
    private int unlockThresholdPhones = 500;

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public int getFirstReleaseRecipientCap() { return firstReleaseRecipientCap; }
    public void setFirstReleaseRecipientCap(int firstReleaseRecipientCap) {
        this.firstReleaseRecipientCap = firstReleaseRecipientCap;
    }

    public String getSendWindowStartLocal() { return sendWindowStartLocal; }
    public void setSendWindowStartLocal(String sendWindowStartLocal) {
        this.sendWindowStartLocal = sendWindowStartLocal;
    }

    public String getSendWindowEndLocal() { return sendWindowEndLocal; }
    public void setSendWindowEndLocal(String sendWindowEndLocal) {
        this.sendWindowEndLocal = sendWindowEndLocal;
    }

    public int getUnlockThresholdPhones() { return unlockThresholdPhones; }
    public void setUnlockThresholdPhones(int unlockThresholdPhones) {
        this.unlockThresholdPhones = unlockThresholdPhones;
    }
}

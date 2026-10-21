package com.imin.iminapi.referral;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Crew-link perks ({@code imin.referral.*}), read per call. Links, attribution and the leaderboard work either way;
 * with {@code perksEnabled} false no perk can be set, shown, unlocked or seen at the door.
 */
@ConfigurationProperties(prefix = "imin.referral")
public class ReferralProperties {

    /** Off until the door scanner shows perks ({@code IMIN_REFERRAL_PERKS_ENABLED}). */
    private boolean perksEnabled = false;

    public boolean isPerksEnabled() { return perksEnabled; }

    public void setPerksEnabled(boolean perksEnabled) { this.perksEnabled = perksEnabled; }
}

package com.imin.iminapi.referral.dto;

import com.imin.iminapi.referral.EventReferralPerk;

import java.time.Instant;

/** The event's perk as the organizer set it; {@code enabled} false = new unlocks stopped. */
public record ReferralPerkDto(String label, int threshold, boolean enabled, Instant updatedAt) {

    public static ReferralPerkDto from(EventReferralPerk p) {
        return new ReferralPerkDto(p.getLabel(), p.getThreshold(), p.isEnabled(), p.getUpdatedAt());
    }
}

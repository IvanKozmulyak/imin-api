package com.imin.iminapi.referral.dto;

/** A valid crew link: {@code perk} is null when the event offers none (or perks are off platform-wide). */
public record PublicReferralLookupResponse(Perk perk) {

    public record Perk(String label, int threshold) {}
}

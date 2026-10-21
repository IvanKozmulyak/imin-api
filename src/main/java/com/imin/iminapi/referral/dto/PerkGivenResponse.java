package com.imin.iminapi.referral.dto;

import java.time.Instant;

/**
 * {@code result}: given | already_given | not_unlocked (organizer and gate), plus invalid | wrong_event (gate).
 * {@code perk} is present for given and already_given only.
 */
public record PerkGivenResponse(String result, Perk perk) {

    public record Perk(String label, Instant givenAt) {}
}

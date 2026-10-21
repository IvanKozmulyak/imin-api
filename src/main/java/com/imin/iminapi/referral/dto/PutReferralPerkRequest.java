package com.imin.iminapi.referral.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * The label is trimmed before validation, so "  ab  " is 2 characters and refused. Length counts code points, as the
 * column's {@code char_length(btrim(label)) BETWEEN 3 AND 60} check does, so an emoji is one character, not two.
 */
public record PutReferralPerkRequest(@NotBlank @Pattern(regexp = "(?s).{3,60}", message = "must be 3 to 60 characters")
                                     String label,
                                     @NotNull @Min(1) @Max(20) Integer threshold) {

    public PutReferralPerkRequest {
        label = label == null ? null : label.trim();
    }
}

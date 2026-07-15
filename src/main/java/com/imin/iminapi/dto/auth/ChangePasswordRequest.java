package com.imin.iminapi.dto.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank String newPassword) {
    /** Mirrors SignupRequest: ≥10 chars, ≥1 letter, ≥1 digit. */
    @AssertTrue(message = "Password must be at least 10 characters and contain a letter and a digit")
    public boolean isPasswordPolicyValid() {
        if (newPassword == null || newPassword.length() < 10) return false;
        boolean hasLetter = false, hasDigit = false;
        for (int i = 0; i < newPassword.length(); i++) {
            char c = newPassword.charAt(i);
            if (Character.isLetter(c)) hasLetter = true;
            else if (Character.isDigit(c)) hasDigit = true;
        }
        return hasLetter && hasDigit;
    }
}

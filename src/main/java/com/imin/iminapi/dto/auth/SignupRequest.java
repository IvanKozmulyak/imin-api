package com.imin.iminapi.dto.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        @NotBlank @Size(max = 255) String orgName,
        @NotBlank @Size(min = 2, max = 2) String country
) {
    /** Spec §11.3 — Password policy: ≥10 chars, ≥1 letter, ≥1 digit. */
    @AssertTrue(message = "Password must be at least 10 characters and contain a letter and a digit")
    public boolean isPasswordPolicyValid() {
        if (password == null || password.length() < 10) return false;
        boolean hasLetter = false, hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) hasLetter = true;
            else if (Character.isDigit(c)) hasDigit = true;
        }
        return hasLetter && hasDigit;
    }
}

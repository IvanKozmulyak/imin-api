package com.imin.iminapi.dto.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        @NotBlank @Size(max = 255) String firstName,
        @NotBlank @Size(max = 255) String lastName,
        @NotBlank @Size(max = 255) String orgName,
        @NotBlank @Size(min = 2, max = 2) String country,
        /**
         * Did the organizer tick the terms box? Optional and NOT enforced: the
         * dashboard has no legal pages to link yet, so requiring it would gate
         * signup on a link that 404s. true ⇒ users.terms_accepted_at is stamped;
         * absent ⇒ "not recorded", never "declined". Enforcement is a follow-up.
         */
        Boolean acceptedTerms,
        /**
         * What the dashboard displayed. Accepted so the client can send it and
         * deliberately not persisted — a client-supplied string that becomes an
         * audit fact is a record that says whatever the client says. The stored
         * version is {@code OrganizerTerms.CURRENT_VERSION}.
         */
        @Size(max = 32) String termsVersion
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

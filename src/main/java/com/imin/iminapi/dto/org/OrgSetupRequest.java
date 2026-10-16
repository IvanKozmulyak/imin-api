package com.imin.iminapi.dto.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The sign-up details a social sign-up skipped, validated like {@code SignupRequest}. {@code termsVersion} must be an
 * accepted organizer-terms version; {@code timezone} (an IANA zone id) is optional and defaults from the country.
 */
public record OrgSetupRequest(
        @NotBlank @Size(max = 255) String firstName,
        @NotBlank @Size(max = 255) String lastName,
        @NotBlank @Size(max = 255) String orgName,
        @NotBlank @Size(min = 2, max = 2) String country,
        @NotBlank @Size(max = 32) String termsVersion,
        @Size(max = 64) String timezone
) {}

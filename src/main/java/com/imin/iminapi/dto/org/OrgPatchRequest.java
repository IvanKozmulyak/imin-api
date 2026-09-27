package com.imin.iminapi.dto.org;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Null leaves a field unchanged; a blank legal field clears it. Legal fields are stripped before validation. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrgPatchRequest(
        @Size(max = 255) String name,
        @Email String contactEmail,
        @Size(min = 2, max = 2) String country,
        @Size(max = 64) String timezone,
        @Size(max = 200) @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must be a single line") String legalName,
        @Size(max = 320) @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must be a single line") String legalContact) {

    public OrgPatchRequest {
        legalName = legalName == null ? null : legalName.strip();
        legalContact = legalContact == null ? null : legalContact.strip();
    }

    public OrgPatchRequest(String name, String contactEmail, String country, String timezone) {
        this(name, contactEmail, country, timezone, null, null);
    }
}

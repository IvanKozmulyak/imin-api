package com.imin.iminapi.dto.org;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrgPatchRequest(
        @Size(max = 255) String name,
        @Email String contactEmail,
        @Size(min = 2, max = 2) String country,
        @Size(max = 64) String timezone) {}

package com.imin.iminapi.dto.org;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record InviteRequest(
        @NotBlank @Email String email,
        @NotBlank @Pattern(regexp = "admin|member") String role) {}

package com.imin.iminapi.dto;

import com.imin.iminapi.model.User;

import java.time.Instant;
import java.util.UUID;

public record UserDto(
        UUID id,
        String email,
        String firstName,
        String lastName,
        String role,
        String avatarInitials,
        UUID orgId,
        Instant createdAt,
        /** BCP-47 language subtag for the dashboard UI (en/es/fr/uk), or null for no preference. */
        String locale
) {
    public static UserDto from(User u) {
        return new UserDto(u.getId(), u.getEmail(),
                u.getFirstName(), u.getLastName(),
                u.getRole().wireValue(), u.getAvatarInitials(),
                u.getOrgId(), u.getCreatedAt(), u.getLocale());
    }
}

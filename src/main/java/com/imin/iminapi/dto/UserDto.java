package com.imin.iminapi.dto;

import com.imin.iminapi.model.User;

import java.time.Instant;
import java.util.UUID;

public record UserDto(
        UUID id,
        String email,
        String name,
        String role,
        String avatarInitials,
        UUID orgId,
        Instant createdAt
) {
    public static UserDto from(User u) {
        return new UserDto(u.getId(), u.getEmail(), u.getName(),
                u.getRole().wireValue(), u.getAvatarInitials(),
                u.getOrgId(), u.getCreatedAt());
    }
}

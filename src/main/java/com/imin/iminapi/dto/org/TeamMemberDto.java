package com.imin.iminapi.dto.org;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.imin.iminapi.model.User;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamMemberDto(
        UUID id, String email, String name, String role,
        String avatarInitials, UUID orgId,
        Instant createdAt, Instant lastActive) {
    public static TeamMemberDto from(User u) {
        return new TeamMemberDto(u.getId(), u.getEmail(), u.getName(),
                u.getRole().wireValue(), u.getAvatarInitials(), u.getOrgId(),
                u.getCreatedAt(), u.getLastActiveAt());
    }
}

package com.imin.iminapi.dto.org;

import java.util.UUID;

public record InviteResponse(UUID inviteId, String email, String role) {}

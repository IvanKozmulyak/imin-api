package com.imin.iminapi.security;

import com.imin.iminapi.model.UserRole;

import java.util.UUID;

public record AuthPrincipal(UUID userId, UUID orgId, UserRole role, UUID sessionId) {}

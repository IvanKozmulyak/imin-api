package com.imin.iminapi.dto.gate;

import java.time.Instant;
import java.util.UUID;

/**
 * Success body for {@code POST /api/v1/gate/login}.
 *
 * <p>The {@code token} is opaque — the FE stores it verbatim and sends it back
 * as {@code Authorization: Bearer <token>}. Deliberately matches the structural
 * shape the spec called out for JWTs (string token + expiry), but the actual
 * encoding is the same SHA-256-hashed-random-bytes scheme used for user
 * sessions, because the codebase has no JWT plumbing today.
 */
public record GateLoginResponse(String token, UUID orgId, String orgName, Instant expiresAt) {}

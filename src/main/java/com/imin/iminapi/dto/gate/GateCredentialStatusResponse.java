package com.imin.iminapi.dto.gate;

import java.time.Instant;

/**
 * Response body for {@code GET /api/v1/orgs/{orgId}/gate/credentials}. Lets
 * the organizer dashboard render "Gate password set on … — [Rotate]" without
 * ever revealing the hash or any rotation-able material. {@code username}
 * mirrors the value gate staff will type — the org slug (or the org UUID as
 * a fallback, which never happens today since slug is NOT NULL since V15).
 */
public record GateCredentialStatusResponse(boolean hasCredential,
                                            Instant lastRotatedAt,
                                            String username) {}

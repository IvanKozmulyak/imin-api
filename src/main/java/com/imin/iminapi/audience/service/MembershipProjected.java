package com.imin.iminapi.audience.service;

import java.util.UUID;

/** The live order path upserted a membership; published inside the projecting transaction. */
public record MembershipProjected(UUID orgId, String normalizedEmail) {}

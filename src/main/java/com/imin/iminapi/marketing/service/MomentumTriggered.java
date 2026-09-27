package com.imin.iminapi.marketing.service;

import java.util.UUID;

/** A Momentum trigger fired for an event and passed the live-suggestion and cooldown guardrails. */
public record MomentumTriggered(UUID orgId, UUID eventId, String trigger) {}

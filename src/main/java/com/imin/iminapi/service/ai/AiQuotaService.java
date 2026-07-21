package com.imin.iminapi.service.ai;

import com.imin.iminapi.dto.ai.AiQuotaResponse;
import com.imin.iminapi.model.AiGenerationUsage;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.AiGenerationUsageRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-user daily quota on AI poster generation — a rolling-24h anti-abuse ceiling layered ON
 * TOP of the burst {@link com.imin.iminapi.security.RateLimiter} (the {@code ai-concept}
 * 10/60min bucket, left unchanged). Only the paid Ideogram image pipeline is metered; text
 * LLM calls are not. Invisible to a normal organizer; only abusive volume trips it.
 *
 * <p>Attempts are recorded BEFORE the paid provider is called, because abuse is measured in
 * attempts, not successes — a caller that grinds a failing endpoint still burns quota.
 */
@Service
public class AiQuotaService {

    private static final Logger log = LoggerFactory.getLogger(AiQuotaService.class);
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final String KIND_IMAGE = "image";

    private final AiGenerationUsageRepository usage;
    private final OrganizationRepository orgs;
    private final AiQuotaProperties props;

    public AiQuotaService(AiGenerationUsageRepository usage, OrganizationRepository orgs, AiQuotaProperties props) {
        this.usage = usage;
        this.orgs = orgs;
        this.props = props;
    }

    /**
     * Enforce and record one poster-generation attempt. Orgs flagged {@code ai_unlimited} are
     * allowed without recording. Otherwise: if the rolling-24h image count for the user is at
     * the limit, throw 429 {@code AI_QUOTA_EXCEEDED} with {@code {limit, used, resetAt}}; else
     * insert a usage row and allow.
     */
    public void checkAndRecordImage(AuthPrincipal p) {
        if (isUnlimited(p.orgId())) {
            return; // record nothing, allow
        }
        UUID userId = p.userId();
        Instant windowStart = Times.nowMicros().minus(WINDOW);
        long used = usage.countByUserIdAndKindAndCreatedAtAfter(userId, KIND_IMAGE, windowStart);
        int limit = props.getImagePerDay();
        if (used >= limit) {
            Instant oldest = usage.findOldestCreatedAt(userId, KIND_IMAGE, windowStart);
            Instant resetAt = (oldest == null ? Times.nowMicros() : oldest).plus(WINDOW);
            log.warn("AI quota exceeded: userId={} kind={} used={} limit={}", userId, KIND_IMAGE, used, limit);
            throw ApiException.aiQuotaExceeded(limit, used, resetAt);
        }
        AiGenerationUsage row = new AiGenerationUsage();
        row.setUserId(userId);
        row.setOrgId(p.orgId());
        row.setKind(KIND_IMAGE);
        usage.save(row);
    }

    /** Read-only quota state for GET /api/v1/ai/quota. */
    public AiQuotaResponse status(AuthPrincipal p) {
        boolean unlimited = isUnlimited(p.orgId());
        int limit = props.getImagePerDay();
        if (unlimited) {
            return new AiQuotaResponse(true, new AiQuotaResponse.Bucket(limit, 0, limit, null));
        }
        Instant windowStart = Times.nowMicros().minus(WINDOW);
        long used = usage.countByUserIdAndKindAndCreatedAtAfter(p.userId(), KIND_IMAGE, windowStart);
        long remaining = Math.max(0, limit - used);
        Instant oldest = used == 0 ? null : usage.findOldestCreatedAt(p.userId(), KIND_IMAGE, windowStart);
        Instant resetAt = oldest == null ? null : oldest.plus(WINDOW);
        return new AiQuotaResponse(false, new AiQuotaResponse.Bucket(limit, used, remaining, resetAt));
    }

    private boolean isUnlimited(UUID orgId) {
        return orgId != null && orgs.findById(orgId).map(Organization::isAiUnlimited).orElse(false);
    }
}

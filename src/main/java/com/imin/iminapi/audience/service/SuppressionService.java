package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.SuppressionEntry;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Suppression management — two scopes.
 *
 * <p><b>Marketing suppression</b> is org-scoped and organizer-owned.  An organizer may
 * suppress a specific membership in their list (e.g., because a subscriber asked
 * off-channel, or the organizer knows the address is stale).  This does NOT
 * affect the shared deliverability pool.
 *
 * <p><b>Deliverability suppression</b> is platform-shared, keyed on
 * {@code normalized_email}, and is written only by system processes (bounce/complaint
 * webhooks from the sending infrastructure).  Org users can READ the flag via the send
 * gate exclusion reason, but cannot add or remove it.
 *
 * <p>Both scopes feed into {@link SendGateService}: a membership is excluded if EITHER
 * suppression type applies.
 *
 * <p>Audit: every organizer-initiated mutation calls
 * {@link AuditLogger#record} with {@link AuditActions#SUPPRESSION_ADDED}.
 */
@Service
public class SuppressionService {

    private final MembershipRepository membershipRepo;
    private final SuppressionRepository suppressionRepo;
    private final AuditLogger auditLogger;

    public SuppressionService(MembershipRepository membershipRepo,
                              SuppressionRepository suppressionRepo,
                              AuditLogger auditLogger) {
        this.membershipRepo = membershipRepo;
        this.suppressionRepo = suppressionRepo;
        this.auditLogger = auditLogger;
    }

    // ── Marketing suppression (org-scoped, organizer-owned) ──────────────────

    /**
     * Add a marketing suppression for a membership in this org.
     * Idempotent — if one already exists, returns it unchanged.
     *
     * @param orgId        the requesting org (from auth context)
     * @param membershipId must belong to orgId (404 otherwise)
     * @param reason       hard-bounce | unsubscribe | spam | manual
     * @param principal    for audit
     */
    @Transactional
    public SuppressionEntry addMarketing(UUID orgId, UUID membershipId, String reason,
                                         AuthPrincipal principal) {
        requireMembership(orgId, membershipId);

        Optional<SuppressionEntry> existing =
                suppressionRepo.findMarketingByOrgAndMembership(orgId, membershipId);
        if (existing.isPresent()) {
            return existing.get();
        }

        SuppressionEntry s = new SuppressionEntry();
        s.setScope(SuppressionEntry.SCOPE_MARKETING);
        s.setOrgId(orgId);
        s.setMembershipId(membershipId);
        s.setReason(reason);
        s.setSystemOwned(false);
        SuppressionEntry saved = suppressionRepo.save(s);

        auditLogger.record(principal, AuditActions.SUPPRESSION_ADDED, "membership", membershipId,
                "Marketing suppression added: reason=" + reason);
        return saved;
    }

    /**
     * Remove a marketing suppression for a membership in this org.
     * No-op if it does not exist.
     */
    @Transactional
    public void removeMarketing(UUID orgId, UUID membershipId, AuthPrincipal principal) {
        requireMembership(orgId, membershipId);
        suppressionRepo.deleteMarketing(orgId, membershipId);
    }

    /**
     * List all marketing suppression entries for an org.
     */
    @Transactional(readOnly = true)
    public List<SuppressionEntry> listMarketing(UUID orgId) {
        return suppressionRepo.findMarketingByOrg(orgId);
    }

    // ── Deliverability suppression (platform-shared, system-owned) ──────────

    /**
     * Record a deliverability suppression (bounce/complaint).
     * Called by internal system processes, NOT by org users.
     * Idempotent — if already suppressed for this email, returns the existing entry.
     *
     * @param normalizedEmail pre-normalized via {@link EmailNormalizer}
     * @param reason          hard-bounce | spam | manual
     */
    @Transactional
    public SuppressionEntry addDeliverability(String normalizedEmail, String reason) {
        Optional<SuppressionEntry> existing =
                suppressionRepo.findDeliverabilityByEmail(normalizedEmail);
        if (existing.isPresent()) {
            return existing.get();
        }

        SuppressionEntry s = new SuppressionEntry();
        s.setScope(SuppressionEntry.SCOPE_DELIVERABILITY);
        s.setNormalizedEmail(normalizedEmail);
        s.setReason(reason);
        s.setSystemOwned(true);
        return suppressionRepo.save(s);
    }

    /**
     * Check whether an email is deliverability-suppressed (shared, any org).
     */
    @Transactional(readOnly = true)
    public boolean isDeliverabilityBlocked(String normalizedEmail) {
        return suppressionRepo.findDeliverabilityByEmail(normalizedEmail).isPresent();
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private Membership requireMembership(UUID orgId, UUID membershipId) {
        return membershipRepo.findByIdAndOrgId(membershipId, orgId)
                .orElseThrow(() -> ApiException.notFound("Membership"));
    }
}

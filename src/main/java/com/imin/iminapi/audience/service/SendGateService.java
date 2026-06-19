package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.dto.ExclusionReason;
import com.imin.iminapi.audience.dto.HandoffResponse;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * FR-SND-1 Send Gate — THE ONLY path that yields sendable recipients.
 *
 * <p>A membership is sendable IFF ALL four conditions hold:
 * <ol>
 *   <li>consent_status != 'unsubscribed'  (M3: reads denormalized column, not consent_records)</li>
 *   <li>NOT marketing-suppressed for this org</li>
 *   <li>NOT deliverability-suppressed (shared, keyed by normalized_email)</li>
 *   <li>consent_basis IS NOT NULL  (no-lawful-basis gate)</li>
 * </ol>
 *
 * <p>Tenant isolation: membershipIds not belonging to orgId are silently excluded —
 * they do not appear in excludedReasons (no existence leak).
 *
 * <p>Re-subscribers pass clause (1) because consent_status = 'subscribed' (M3 regression test).
 */
@Service
public class SendGateService {

    private final MembershipRepository membershipRepo;
    private final ConsumerRepository consumerRepo;
    private final SuppressionRepository suppressionRepo;
    private final AuditLogger auditLogger;

    public SendGateService(MembershipRepository membershipRepo,
                           ConsumerRepository consumerRepo,
                           SuppressionRepository suppressionRepo,
                           AuditLogger auditLogger) {
        this.membershipRepo = membershipRepo;
        this.consumerRepo = consumerRepo;
        this.suppressionRepo = suppressionRepo;
        this.auditLogger = auditLogger;
    }

    public record GateResult(List<UUID> sendable, List<ExclusionReason> excluded) {}

    /**
     * Evaluate the send gate for the given membership IDs within an org.
     * Returns which IDs are sendable and which are excluded (with reason).
     */
    @Transactional(readOnly = true)
    public GateResult evaluate(UUID orgId, Collection<UUID> membershipIds) {
        if (membershipIds == null || membershipIds.isEmpty()) {
            return new GateResult(List.of(), List.of());
        }

        // Load only memberships belonging to this org — tenant isolation
        List<Membership> memberships = membershipRepo.findByIdsAndOrgId(membershipIds, orgId);

        // Batch: marketing suppressions for this org
        List<UUID> candidateIds = memberships.stream()
                .map(Membership::getMembershipId).toList();
        Set<UUID> marketingSuppressed = new HashSet<>(
                suppressionRepo.findMarketingSuppressedMembershipIds(orgId, candidateIds));

        // Batch: resolve consumer emails for deliverability check
        List<UUID> consumerIds = memberships.stream()
                .map(Membership::getConsumerId).distinct().toList();
        Map<UUID, String> emailByConsumerId = consumerRepo
                .findAllByConsumerIdIn(consumerIds).stream()
                .collect(Collectors.toMap(Consumer::getConsumerId, Consumer::getNormalizedEmail));

        // Batch: deliverability suppressions (shared, keyed by email)
        Collection<String> allEmails = emailByConsumerId.values();
        Set<String> deliverabilityBlocked = allEmails.isEmpty() ? Set.of()
                : new HashSet<>(suppressionRepo.findDeliverabilityEmailsIn(allEmails));

        List<UUID> sendable = new ArrayList<>();
        List<ExclusionReason> excluded = new ArrayList<>();

        for (Membership m : memberships) {
            UUID mid = m.getMembershipId();
            String email = emailByConsumerId.get(m.getConsumerId());

            // Clause 1: not unsubscribed (M3 — denormalized consent_status, catches re-subscribers correctly)
            if ("unsubscribed".equals(m.getConsentStatus())) {
                excluded.add(new ExclusionReason(mid, "marketing_unsubscribed"));
                continue;
            }
            // Clause 2: not marketing-suppressed for this org
            if (marketingSuppressed.contains(mid)) {
                excluded.add(new ExclusionReason(mid, "marketing_suppressed"));
                continue;
            }
            // Clause 3: not deliverability-suppressed (shared by email across all orgs)
            if (email != null && deliverabilityBlocked.contains(email)) {
                excluded.add(new ExclusionReason(mid, "deliverability_suppressed"));
                continue;
            }
            // Clause 4: has lawful basis
            if (m.getConsentBasis() == null) {
                excluded.add(new ExclusionReason(mid, "no_lawful_basis"));
                continue;
            }
            sendable.add(mid);
        }

        return new GateResult(sendable, excluded);
    }

    /**
     * Execute handoff: run the gate and return a HandoffResponse. Audited.
     */
    @Transactional
    public HandoffResponse handoff(UUID orgId, Collection<UUID> membershipIds, AuthPrincipal principal) {
        GateResult result = evaluate(orgId, membershipIds);

        auditLogger.record(principal, AuditActions.AUDIENCE_HANDOFF, "membership", null,
                "Handoff: " + result.sendable().size() + " sendable, "
                        + result.excluded().size() + " excluded");

        return new HandoffResponse(
                result.sendable().size(),
                result.sendable().stream().map(UUID::toString).toList(),
                result.excluded(),
                "/campaigns"
        );
    }
}

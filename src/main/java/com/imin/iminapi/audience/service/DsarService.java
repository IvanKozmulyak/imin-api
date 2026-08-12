package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * DSAR (Data Subject Access Request) operations — Art.15/16/17/21 GDPR.
 *
 * <p>Tier C:
 * <ul>
 *   <li>access/export/rectify — operator-facing, always ungated, synchronous.</li>
 *   <li>object (Art.21) — synchronous, always succeeds immediately.</li>
 *   <li>erase (Art.17) — REAL cascade with 30-day grace period:
 *       <ol>
 *         <li>Sets membership.status='erase_pending' + erase_at=now()+30d.</li>
 *         <li>The scheduled erasure job executes the destructive cascade after 30d.</li>
 *         <li>Leaves immutable tombstone in audit_logs.</li>
 *         <li>Consumer row survives if another org still references it.</li>
 *       </ol>
 *   </li>
 * </ul>
 */
@Service
public class DsarService {

    private final MembershipRepository membershipRepo;
    private final ConsumerRepository consumerRepo;
    private final ConsentRecordRepository consentRepo;
    private final SuppressionRepository suppressionRepo;
    private final ConsentService consentService;
    private final AuditLogger auditLogger;
    private final com.imin.iminapi.marketing.repository.CampaignRecipientRepository campaignRecipientRepo;
    private final NotifySubscriptionRepository notifySubscriptionRepo;

    public DsarService(MembershipRepository membershipRepo,
                       ConsumerRepository consumerRepo,
                       ConsentRecordRepository consentRepo,
                       SuppressionRepository suppressionRepo,
                       ConsentService consentService,
                       AuditLogger auditLogger,
                       com.imin.iminapi.marketing.repository.CampaignRecipientRepository campaignRecipientRepo,
                       NotifySubscriptionRepository notifySubscriptionRepo) {
        this.membershipRepo = membershipRepo;
        this.consumerRepo = consumerRepo;
        this.consentRepo = consentRepo;
        this.suppressionRepo = suppressionRepo;
        this.consentService = consentService;
        this.auditLogger = auditLogger;
        this.campaignRecipientRepo = campaignRecipientRepo;
        this.notifySubscriptionRepo = notifySubscriptionRepo;
    }

    /** Art.15 access — returns the membership (caller maps to DTO). Audited. */
    @Transactional(readOnly = true)
    public Membership access(UUID orgId, UUID membershipId, AuthPrincipal principal) {
        Membership m = require(orgId, membershipId);
        auditLogger.record(principal, AuditActions.DSAR_ACCESS, "membership", membershipId, "DSAR access");
        return m;
    }

    /** Art.15 export — returns the membership for export. Audited. */
    @Transactional(readOnly = true)
    public Membership export(UUID orgId, UUID membershipId, AuthPrincipal principal) {
        Membership m = require(orgId, membershipId);
        auditLogger.record(principal, AuditActions.DSAR_EXPORT, "membership", membershipId, "DSAR export");
        return m;
    }

    /** Art.16 rectification — updates display_name/city/notes fields. Audited. */
    @Transactional
    public Membership rectify(UUID orgId, UUID membershipId, String displayName,
                              String city, String notes, AuthPrincipal principal) {
        Membership m = require(orgId, membershipId);
        if (displayName != null) m.setDisplayName(displayName);
        if (city != null) m.setCity(city);
        if (notes != null) m.setNotes(notes);
        Membership saved = membershipRepo.save(m);
        auditLogger.record(principal, AuditActions.DSAR_RECTIFY, "membership", membershipId, "DSAR rectify");
        return saved;
    }

    /**
     * Art.21 object — synchronous immediate effect.
     * Sets consent_status='unsubscribed' and appends consent record.
     */
    @Transactional
    public void object(UUID orgId, UUID membershipId, AuthPrincipal principal) {
        require(orgId, membershipId); // verify exists + belongs to org
        consentService.unsubscribe(orgId, membershipId, "dsar_object", "email", ConsentOrigin.DATA_SUBJECT, principal);
        auditLogger.record(principal, AuditActions.DSAR_OBJECT, "membership", membershipId,
                "DSAR object (Art.21) — unsubscribed immediately");
    }

    /**
     * Art.17 erasure — schedules 30-day grace period.
     * Sets status='erase_pending', erase_at=now()+30d.
     * The {@link AudienceErasureJob} executes the cascade after grace period.
     */
    @Transactional
    public void requestErase(UUID orgId, UUID membershipId, AuthPrincipal principal) {
        Membership m = require(orgId, membershipId);
        m.setStatus("erase_pending");
        m.setEraseAt(Instant.now().plus(30, ChronoUnit.DAYS));
        membershipRepo.save(m);
        auditLogger.record(principal, AuditActions.DSAR_ERASE_REQUESTED, "membership", membershipId,
                "DSAR erase requested — pending 30d grace period");
    }

    /**
     * Execute erasure (called by job after 30d). Destructive cascade:
     * 1. Delete consent_records (cascades via FK ON DELETE CASCADE on membership_id).
     * 2. Delete marketing suppression entries for this membership.
     * 3. Delete this org's notify_subscriptions rows for the erased address.
     * 4. Delete the membership row itself.
     * 5. Delete Consumer if no other memberships reference it.
     * 6. Write tombstone to audit_logs.
     */
    @Transactional
    public void executeErase(UUID orgId, UUID membershipId, AuthPrincipal principal) {
        Membership m = membershipRepo.findByIdAndOrgId(membershipId, orgId).orElse(null);
        if (m == null) return; // already erased
        UUID consumerId = m.getConsumerId();

        // 2. Marketing suppressions cascade via FK; also explicitly clean up
        suppressionRepo.deleteMarketingByOrgAndMembership(orgId, membershipId);

        // Spec §7: null campaign_recipients PII BEFORE the membership hard-delete, keeping
        // status/skip_reason as an anonymous audit record. V53's ON DELETE SET NULL then
        // lets the membership delete proceed instead of blocking AudienceErasureJob forever.
        campaignRecipientRepo.redactPiiByMembershipId(membershipId);

        // 3. Notify-me subscriptions. These live outside the consumer/membership graph —
        // keyed by (event, raw email), no org column — so nothing above reaches them and an
        // "erased" buyer's address used to survive here, still queued for a release email.
        // Scope by the parent event's org: DSAR is org-scoped, so another org's subscription
        // for the same address is that org's data and is left alone. Read the address BEFORE
        // step 5 possibly deletes the consumer row it lives on.
        consumerRepo.findByConsumerId(consumerId)
                .map(c -> c.getNormalizedEmail())
                .filter(email -> email != null && !email.isBlank())
                .ifPresent(email -> notifySubscriptionRepo.deleteByOrgIdAndEmail(orgId, email));

        // 4. Delete membership (consent_records cascade via FK ON DELETE CASCADE)
        membershipRepo.deleteByIdAndOrgId(membershipId, orgId);

        // 5. Delete Consumer if no remaining memberships reference it
        long remaining = consumerRepo.countMembershipsByConsumerId(consumerId);
        if (remaining == 0) {
            consumerRepo.deleteByConsumerId(consumerId);
        }

        // 6. Tombstone in audit_logs (immutable, REQUIRES_NEW inside AuditLogger)
        auditLogger.record(principal, AuditActions.DSAR_ERASE_EXECUTED, "membership", membershipId,
                "DSAR erase executed — org=" + orgId);
    }

    private Membership require(UUID orgId, UUID membershipId) {
        return membershipRepo.findByIdAndOrgId(membershipId, orgId)
                .orElseThrow(() -> ApiException.notFound("Membership"));
    }
}

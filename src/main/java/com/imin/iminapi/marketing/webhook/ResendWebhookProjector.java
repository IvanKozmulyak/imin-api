package com.imin.iminapi.marketing.webhook;

import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.audience.service.SuppressionService;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.model.ProviderEvent;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.ComplaintRateBreaker;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Projects a verified, deduped Resend event onto the recipient row AND back
 * into audience truth (spec §2.5 step 5, §7). Runs in the same transaction as
 * the dedup INSERT, so if projection throws the claim rolls back and a Resend
 * retry re-processes cleanly.
 *
 * <p>The recipient row carries no org_id (spec §2.2 V53), so org is derived
 * from the campaign — only the complaint branch needs it (marketing
 * suppression + breaker are org-scoped).
 *
 * <p>Unsubscribes are intentionally NOT projected here — Resend emits no
 * distinct {@code email.unsubscribed} type; the {@code unsubscribed} status
 * comes from the owned RFC-8058 one-click endpoint (Phase 2), which is why
 * {@code ConsentService} is not a dependency of this projector.
 */
@Service
public class ResendWebhookProjector {

    private final CampaignRecipientRepository recipientRepo;
    private final CampaignRepository campaignRepo;
    private final MembershipRepository membershipRepo;
    private final SuppressionService suppressionService;
    private final ComplaintRateBreaker complaintRateBreaker;

    public ResendWebhookProjector(CampaignRecipientRepository recipientRepo,
                                  CampaignRepository campaignRepo,
                                  MembershipRepository membershipRepo,
                                  SuppressionService suppressionService,
                                  ComplaintRateBreaker complaintRateBreaker) {
        this.recipientRepo = recipientRepo;
        this.campaignRepo = campaignRepo;
        this.membershipRepo = membershipRepo;
        this.suppressionService = suppressionService;
        this.complaintRateBreaker = complaintRateBreaker;
    }

    @Transactional
    public void project(UUID campaignId, UUID recipientId, UUID membershipId,
                        String email, String type, Instant occurredAt) {
        CampaignRecipient r = recipientId == null ? null
                : recipientRepo.findById(recipientId).orElse(null);
        switch (type) {
            case ProviderEvent.TYPE_DELIVERED -> {
                if (r != null) { r.setStatus("delivered"); r.setDeliveredAt(occurredAt); touch(r, occurredAt); }
            }
            case ProviderEvent.TYPE_BOUNCED -> {
                if (r != null) { r.setStatus("bounced"); touch(r, occurredAt); }
                if (email != null && !email.isBlank()) {
                    suppressionService.addDeliverability(EmailNormalizer.normalize(email), "hard-bounce");
                }
            }
            case ProviderEvent.TYPE_COMPLAINED -> {
                if (r != null) { r.setStatus("complained"); touch(r, occurredAt); }
                UUID orgId = campaignId == null ? null
                        : campaignRepo.findById(campaignId).map(Campaign::getOrgId).orElse(null);
                if (membershipId != null && orgId != null) {
                    // MUST pass a non-null, org-scoped SYSTEM principal — addMarketing's
                    // AuditLogger.record(principal, SUPPRESSION_ADDED, ...) dereferences
                    // principal.orgId() inside a best-effort try that SWALLOWS a null-principal
                    // NPE, so passing null silently loses the compliance-sensitive
                    // SUPPRESSION_ADDED audit row on every complaint. A system principal
                    // carrying the real orgId attributes the row correctly.
                    AuthPrincipal systemPrincipal = new AuthPrincipal(null, orgId, UserRole.MEMBER, null);
                    suppressionService.addMarketing(orgId, membershipId, "spam", systemPrincipal);
                }
                complaintRateBreaker.evaluate(campaignId, orgId);
            }
            case ProviderEvent.TYPE_OPENED -> {
                if (r != null) { r.setOpenedAt(occurredAt); touch(r, occurredAt); }
                if (membershipId != null) membershipRepo.recordEmailOpen(membershipId, occurredAt);
            }
            case ProviderEvent.TYPE_CLICKED -> {
                if (r != null) { r.setClickedAt(occurredAt); touch(r, occurredAt); }
                if (membershipId != null) membershipRepo.recordEmailClick(membershipId, occurredAt);
            }
            default -> { /* unknown type — logged and deduped upstream, no projection */ }
        }
        if (r != null) recipientRepo.save(r);
    }

    private void touch(CampaignRecipient r, Instant occurredAt) {
        r.setLastEventAt(occurredAt);
    }
}

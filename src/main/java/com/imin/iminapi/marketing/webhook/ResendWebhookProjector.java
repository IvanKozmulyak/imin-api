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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(ResendWebhookProjector.class);

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

    /**
     * How many transient bounces the same membership may accumulate before it is suppressed
     * for its own org. Three is one more than a plausible run of bad luck (a weekend of
     * greylisting, a mailbox emptied on Monday) and far short of "we keep mailing a dead box".
     */
    static final int SOFT_BOUNCE_SUPPRESS_AFTER = 3;

    /**
     * @param bounceType Resend's {@code data.bounce.type} for an {@code email.bounced} event —
     *                   {@code Permanent} | {@code Transient} | {@code Undetermined}, and null
     *                   for every other event type (or a bounce that carried none).
     */
    @Transactional
    public void project(UUID campaignId, UUID recipientId, UUID membershipId,
                        String email, String type, String bounceType, Instant occurredAt) {
        // A signed body with no "type" is malformed, not fatal (mkt-core-14). provider_events.type
        // is nullable so the dedup claim succeeds, and a String switch on null throws NPE — which
        // rolled that claim back with it, so every Resend retry repeated the 500 for ever instead
        // of being deduped away. Treat it exactly like an unknown type: ignore and ack.
        if (type == null || type.isBlank()) {
            log.info("[resend-projector] event with no type for recipient {} — ignored", recipientId);
            return;
        }
        CampaignRecipient r = recipientId == null ? null
                : recipientRepo.findById(recipientId).orElse(null);
        switch (type) {
            case ProviderEvent.TYPE_DELIVERED -> {
                if (r != null) { r.setStatus("delivered"); r.setDeliveredAt(occurredAt); touch(r, occurredAt); }
            }
            case ProviderEvent.TYPE_BOUNCED -> {
                // mkt-edge-6: the deliverability list is platform-shared, system-owned and has
                // NO removal path anywhere in src/main — a row written here blinds every
                // organizer for that address for ever. Only a bounce Resend itself calls
                // Permanent earns that. Transient/Undetermined/absent are a temporary
                // condition at the receiving end (full mailbox, greylisting, DNS blip); an
                // absent type is deliberately read as transient, because the shared list must
                // never be written on a guess.
                boolean permanent = isPermanentBounce(bounceType);
                if (r != null) {
                    r.setStatus("bounced");
                    r.setErrorCode(permanent ? "hard_bounce" : "soft_bounce");
                    touch(r, occurredAt);
                }
                if (permanent) {
                    if (email != null && !email.isBlank()) {
                        suppressionService.addDeliverability(
                                EmailNormalizer.normalize(email), "hard-bounce");
                    }
                } else {
                    escalateRepeatedSoftBounce(campaignId, membershipId, r);
                }
            }
            case ProviderEvent.TYPE_COMPLAINED -> {
                if (r != null) { r.setStatus("complained"); touch(r, occurredAt); }
                UUID orgId = orgIdOf(campaignId);
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

    /** Resend's own classification. Anything we do not recognise is NOT permanent. */
    private static boolean isPermanentBounce(String bounceType) {
        return bounceType != null && ("permanent".equalsIgnoreCase(bounceType.trim())
                || "hard".equalsIgnoreCase(bounceType.trim()));
    }

    private UUID orgIdOf(UUID campaignId) {
        return campaignId == null ? null
                : campaignRepo.findById(campaignId).map(Campaign::getOrgId).orElse(null);
    }

    /**
     * A run of transient bounces to the same membership does mean something — but it means it
     * for the org whose mail keeps failing, not for the platform. So the escalation writes the
     * ORG-SCOPED marketing suppression (the same row the complaint branch writes), never the
     * cross-org deliverability list: another organizer's sending reputation and domain are a
     * different experiment, and only a Permanent bounce is evidence about the address itself.
     */
    private void escalateRepeatedSoftBounce(UUID campaignId, UUID membershipId, CampaignRecipient r) {
        if (membershipId == null) return;
        UUID orgId = orgIdOf(campaignId);
        if (orgId == null) return;
        // Flush this bounce before counting: the count is over campaign_recipients.error_code
        // and must include the row we just stamped, not lag one event behind it.
        if (r != null) recipientRepo.saveAndFlush(r);
        long soft = recipientRepo.countSoftBouncesByMembership(membershipId);
        if (soft < SOFT_BOUNCE_SUPPRESS_AFTER) return;
        log.info("[resend-projector] membership {} has {} transient bounces — suppressing for org {}",
                membershipId, soft, orgId);
        // Same non-null, org-scoped SYSTEM principal the complaint branch documents: addMarketing
        // audits through AuditLogger, which silently drops an org-less row.
        AuthPrincipal systemPrincipal = new AuthPrincipal(null, orgId, UserRole.MEMBER, null);
        suppressionService.addMarketing(orgId, membershipId, "soft-bounce", systemPrincipal);
    }
}

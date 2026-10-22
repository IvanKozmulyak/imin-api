package com.imin.iminapi.marketing.webhook;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentConfirmationTokenRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.service.MarketingOptOutRecorder;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentChanged;
import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.audience.service.SuppressionService;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.ProviderEvent;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.ComplaintRateBreaker;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 *
 * <p>Opens and clicks stamp only the recipient row ({@code opened_at}, {@code clicked_at},
 * {@code ticket_clicked_at}; latest instant wins, {@code last_event_at} untouched), never the
 * membership, and nothing for a membership that objected. An unsubscribe-link click writes nothing.
 */
@Service
public class ResendWebhookProjector {

    private final CampaignRecipientRepository recipientRepo;
    private final CampaignRepository campaignRepo;
    private static final Logger log = LoggerFactory.getLogger(ResendWebhookProjector.class);

    private final MembershipRepository membershipRepo;
    private final SuppressionService suppressionService;
    private final ComplaintRateBreaker complaintRateBreaker;
    private final ApplicationEventPublisher events;
    private final FanFeatureRepository fanFeatureRepo;
    private final ConsentConfirmationTokenRepository confirmationTokens;
    private final MarketingEmailProperties marketingProps;
    private final ConsumerRepository consumers;
    private final MarketingOptOutRecorder optOuts;

    public ResendWebhookProjector(CampaignRecipientRepository recipientRepo,
                                  CampaignRepository campaignRepo,
                                  MembershipRepository membershipRepo,
                                  SuppressionService suppressionService,
                                  ComplaintRateBreaker complaintRateBreaker,
                                  ApplicationEventPublisher events,
                                  FanFeatureRepository fanFeatureRepo,
                                  ConsentConfirmationTokenRepository confirmationTokens,
                                  MarketingEmailProperties marketingProps,
                                  ConsumerRepository consumers,
                                  MarketingOptOutRecorder optOuts) {
        this.recipientRepo = recipientRepo;
        this.campaignRepo = campaignRepo;
        this.membershipRepo = membershipRepo;
        this.suppressionService = suppressionService;
        this.complaintRateBreaker = complaintRateBreaker;
        this.events = events;
        this.fanFeatureRepo = fanFeatureRepo;
        this.confirmationTokens = confirmationTokens;
        this.marketingProps = marketingProps;
        this.consumers = consumers;
        this.optOuts = optOuts;
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
        project(campaignId, recipientId, membershipId, email, type, bounceType, occurredAt, null);
    }

    /** @param clickLink Resend's {@code data.click.link} for an {@code email.clicked} event, else null. */
    @Transactional
    public void project(UUID campaignId, UUID recipientId, UUID membershipId,
                        String email, String type, String bounceType, Instant occurredAt, String clickLink) {
        // A signed body with no "type" is malformed, not fatal (mkt-core-14). provider_events.type
        // is nullable so the dedup claim succeeds, and a String switch on null throws NPE — which
        // rolled that claim back with it, so every Resend retry repeated the 500 for ever instead
        // of being deduped away. Treat it exactly like an unknown type: ignore and ack.
        if (type == null || type.isBlank()) {
            log.info("[resend-projector] event with no type for recipient {} — ignored", recipientId);
            return;
        }
        // Every branch writes the row through a column-scoped UPDATE, never an entity save: the entity has
        // no @Version, so a save of a row read earlier would put back columns a concurrent event committed.
        switch (type) {
            case ProviderEvent.TYPE_DELIVERED -> {
                if (recipientId != null) recipientRepo.markDelivered(recipientId, occurredAt);
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
                if (permanent) {
                    if (email != null && !email.isBlank()) {
                        suppressionService.addDeliverabilityIfAbsent(
                                EmailNormalizer.normalize(email), "hard-bounce");
                    }
                } else {
                    escalateRepeatedSoftBounce(campaignId, membershipId, recipientId);
                }
                // Recipient last, as in the complaint branch: holding this row while inserting the suppression
                // a concurrent complaint already holds deadlocked the two.
                if (recipientId != null) {
                    recipientRepo.markBounced(recipientId, permanent ? "hard_bounce" : "soft_bounce", occurredAt);
                }
                // After the row records the bounce, so the org window's hard-bounce count includes it.
                complaintRateBreaker.evaluate(campaignId, orgIdOf(campaignId));
            }
            case ProviderEvent.TYPE_COMPLAINED -> {
                UUID orgId = orgIdOf(campaignId);
                if (membershipId != null && orgId != null) {
                    // MUST pass a non-null, org-scoped SYSTEM principal — addMarketingIfAbsent's
                    // AuditLogger.record(principal, SUPPRESSION_ADDED, ...) dereferences
                    // principal.orgId() inside a best-effort try that SWALLOWS a null-principal
                    // NPE, so passing null silently loses the compliance-sensitive
                    // SUPPRESSION_ADDED audit row on every complaint. A system principal
                    // carrying the real orgId attributes the row correctly.
                    AuthPrincipal systemPrincipal = new AuthPrincipal(null, orgId, UserRole.MEMBER, null);
                    suppressionService.addMarketingIfAbsent(orgId, membershipId, "spam", systemPrincipal);
                    // A spam report is the person objecting; no sticky opt-out row, only the profiling flag.
                    // Membership lock before fan_features, the projector's order; taste clears in this transaction.
                    membershipRepo.lockByIdAndOrgId(membershipId, orgId).ifPresent(m -> {
                        m.setObjectedProfiling(true);
                        membershipRepo.save(m);
                        fanFeatureRepo.clearProfiling(membershipId);
                        membershipRepo.updateNps(membershipId, null);
                        confirmationTokens.clearHeldScores(membershipId);
                        events.publishEvent(new ConsentChanged(orgId, membershipId, false));
                    });
                }
                // After the membership lock, so this transaction locks membership then recipient.
                if (recipientId != null) recipientRepo.markComplained(recipientId, occurredAt);
                complaintRateBreaker.evaluate(campaignId, orgId);
            }
            case ProviderEvent.TYPE_OPENED -> {
                if (recipientId != null) recipientRepo.markOpened(recipientId, occurredAt);
            }
            case ProviderEvent.TYPE_CLICKED -> {
                if (recipientId != null) projectClick(campaignId, recipientId, occurredAt, clickLink);
            }
            default -> { /* unknown type — logged and deduped upstream, no projection */ }
        }
    }

    /**
     * An event no campaign row or reminder claims (transactional mail, or a campaign row already purged): only a
     * Permanent bounce is acted on, by listing the address as undeliverable; nothing else is projected.
     */
    @Transactional
    public void projectUnmatched(String type, String email, String bounceType) {
        if (!ProviderEvent.TYPE_BOUNCED.equals(type) || !isPermanentBounce(bounceType)) return;
        if (email == null || email.isBlank()) return;
        suppressionService.addDeliverabilityIfAbsent(EmailNormalizer.normalize(email), "hard-bounce");
    }

    /**
     * A complaint about a tagged campaign email whose row is gone or never recorded. The org comes from the campaign:
     * the member at that address is suppressed and objects to profiling as in the complaint branch; with no member,
     * the per-address opt-out from this org is recorded instead. Either way the breaker counts it.
     */
    @Transactional
    public void projectTaggedComplaint(UUID campaignId, String email, Instant occurredAt) {
        UUID orgId = orgIdOf(campaignId);
        if (orgId == null || email == null || email.isBlank()) return;
        String address = EmailNormalizer.normalize(email);
        UUID membershipId = consumers.findByNormalizedEmail(address)
                .flatMap(cn -> membershipRepo.findByOrgIdAndConsumerId(orgId, cn.getConsumerId()))
                .map(Membership::getMembershipId)
                .orElse(null);
        if (membershipId != null) {
            project(campaignId, null, membershipId, address, ProviderEvent.TYPE_COMPLAINED, null, occurredAt);
            return;
        }
        try {
            optOuts.record(address, orgId, "email", "complaint");
        } catch (org.springframework.dao.DataIntegrityViolationException lostRace) {
            // The row this wanted already exists.
        } catch (RuntimeException e) {
            log.error("[resend-projector] opt-out for a complaint on campaign {} not recorded: {}", campaignId,
                    e.getClass().getSimpleName());
        }
        complaintRateBreaker.evaluate(campaignId, orgId);
    }

    private void projectClick(UUID campaignId, UUID recipientId, Instant occurredAt, String clickLink) {
        UUID eventId = campaignId == null ? null
                : campaignRepo.findById(campaignId).map(Campaign::getEventId).orElse(null);
        switch (ClickedLink.classify(clickLink, eventId, marketingProps.getBuyerSiteBaseUrl(),
                marketingProps.getApiPublicBaseUrl())) {
            case UNSUBSCRIBE -> { /* the opt-out is recorded by the unsubscribe endpoint, not counted as a click */ }
            case TICKET -> recipientRepo.markTicketClicked(recipientId, occurredAt);
            case OTHER, UNKNOWN -> recipientRepo.markClicked(recipientId, occurredAt);
        }
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
    private void escalateRepeatedSoftBounce(UUID campaignId, UUID membershipId, UUID recipientId) {
        if (membershipId == null) return;
        UUID orgId = orgIdOf(campaignId);
        if (orgId == null) return;
        // Read before markBounced, counting this bounce as markBounced will record it.
        long soft = recipientRepo.countSoftBouncesWithThisBounce(membershipId, recipientId);
        if (soft < SOFT_BOUNCE_SUPPRESS_AFTER) return;
        log.info("[resend-projector] membership {} has {} transient bounces — suppressing for org {}",
                membershipId, soft, orgId);
        // Same non-null, org-scoped SYSTEM principal the complaint branch documents: addMarketingIfAbsent
        // audits through AuditLogger, which silently drops an org-less row.
        AuthPrincipal systemPrincipal = new AuthPrincipal(null, orgId, UserRole.MEMBER, null);
        suppressionService.addMarketingIfAbsent(orgId, membershipId, "soft-bounce", systemPrincipal);
    }
}

package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.service.ConsentGate;
import com.imin.iminapi.audienceplan.service.RetentionCutoff;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.service.ticket.TicketsIssuedEvent;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Listens for {@link TicketsIssuedEvent} AFTER_COMMIT and upserts the
 * Consumer + Membership rows, then recomputes all derived aggregates from source.
 *
 * <p>S3: first_touch_src = 'organic' unconditionally in Tier C. (Orders DO carry an anon_id
 * and the landing utm_* since V62, but first-touch attribution is a separate read-model and
 * is not wired here — this stays 'organic' rather than guessing.)
 * <p>S5: an email-consent row comes only from the order's own ticked box with its sentence ('explicit') or from a
 * paid order whose notice the buyer read without ticking refuse ('soft_opt_in'); a refusal is recorded as an objection.
 * <p>S1: aggregates are DERIVED from source rows (not incremented).
 */
@Component
public class AudienceOrderProjector {

    private static final Logger log = LoggerFactory.getLogger(AudienceOrderProjector.class);
    private static final String ERASE_PENDING = "erase_pending";
    static final String SOFT_OPT_IN = "soft_opt_in";
    static final String REFUSAL_SOURCE = "checkout_refusal";

    private final OrderRepository orderRepo;
    private final ConsumerRepository consumerRepo;
    private final MembershipRepository membershipRepo;
    private final MembershipProjector projector;
    private final ConsentService consentService;
    private final ApplicationEventPublisher events;
    private final OrganizationRepository orgRepo;
    private final AudiencePlanLogic logic;
    private final TransactionTemplate requiresNew;
    private final ErasedAddressRepository erasedAddressRepo;
    private final RetentionCutoff retentionCutoff;
    private final MarketingOptedOutCheck optedOutCheck;
    private final ConsentRecordRepository consentRecords;

    public AudienceOrderProjector(OrderRepository orderRepo,
                                   ConsumerRepository consumerRepo,
                                   MembershipRepository membershipRepo,
                                   MembershipProjector projector,
                                   ConsentService consentService,
                                   ApplicationEventPublisher events,
                                   OrganizationRepository orgRepo,
                                   AudiencePlanLogic logic,
                                   PlatformTransactionManager transactionManager,
                                   ErasedAddressRepository erasedAddressRepo,
                                   RetentionCutoff retentionCutoff,
                                   MarketingOptedOutCheck optedOutCheck,
                                   ConsentRecordRepository consentRecords) {
        this.optedOutCheck = optedOutCheck;
        this.consentRecords = consentRecords;
        this.erasedAddressRepo = erasedAddressRepo;
        this.retentionCutoff = retentionCutoff;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.orgRepo = orgRepo;
        this.logic = logic;
        this.orderRepo = orderRepo;
        this.consumerRepo = consumerRepo;
        this.membershipRepo = membershipRepo;
        this.projector = projector;
        this.consentService = consentService;
        this.events = events;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onTicketsIssued(TicketsIssuedEvent event) {
        try {
            // The transaction ends inside the try, so a rollback-only mark or a failed commit flush is caught too.
            requiresNew.executeWithoutResult(status -> project(event.orderId()));
        } catch (Exception e) {
            // No throwable: its message carries the SQL detail, buyer email included, past the redaction.
            log.error("AudienceOrderProjector failed for order {}: {}: {}", event.orderId(),
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
        }
    }

    private void project(UUID orderId) {
        Order order = orderRepo.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("AudienceOrderProjector: Order {} not found — skipping", orderId);
            return;
        }
        String normalizedEmail = EmailNormalizer.normalize(order.getEmail());
        UUID orgId = order.getOrgId();
        Membership m = projectMembership(orgId, normalizedEmail, order.getEmail(),
                order.getBuyerPhone(), order.isSmsMarketingOptIn());
        if (order.isMarketingRefused()) {
            // The refusal is the buyer's latest act: it beats a tick on the same order and any older consent.
            recordRefusal(orgId, m, order);
        } else if (!recordTickedOptIn(orgId, m, order.isMarketingOptIn(), order.getId(),
                order.getMarketingOptInProof(), provenTextVersion(order)) && softOptInEligible(order, m)) {
            captureSoftOptIn(orgId, m, order);
        }
        // Downstream projections read the membership, so they follow this commit, not the order's.
        events.publishEvent(new MembershipProjected(orgId, normalizedEmail));
    }

    /** Locked membership for (org, consumer), inserted first when absent; first_touch_src 'organic' (S3). */
    public static Membership lockOrCreateMembership(MembershipRepository memberships, UUID orgId, UUID consumerId,
                                                    String displayName) {
        Optional<Membership> existing = memberships.lockByOrgIdAndConsumerId(orgId, consumerId);
        if (existing.isPresent()) return existing.get();
        memberships.insertIfAbsent(UUID.randomUUID(), orgId, consumerId, displayName, Instant.now());
        return memberships.lockByOrgIdAndConsumerId(orgId, consumerId)
                .orElseThrow(() -> new IllegalStateException("Membership missing after insert-if-absent"));
    }

    /** Consumer for the address, inserted first when absent; an existing consumer is never changed. */
    public static Consumer getOrCreateConsumer(ConsumerRepository consumers, String normalizedEmail,
                                               String displayName) {
        Optional<Consumer> existing = consumers.findByNormalizedEmail(normalizedEmail);
        if (existing.isPresent()) return existing.get();
        // ON CONFLICT, not a caught duplicate: a failed INSERT aborts the Postgres transaction,
        // so a re-read after it could never run.
        consumers.insertIfAbsent(UUID.randomUUID(), normalizedEmail, displayName, Instant.now());
        return consumers.findByNormalizedEmail(normalizedEmail)
                .orElseThrow(() -> new IllegalStateException("Consumer missing after insert-if-absent"));
    }

    /**
     * The order's label version, kept only when it is on the organizer-named allowlist and the
     * sentence the buyer read contains the organizer's name; otherwise null (a client claim alone
     * never makes a consent count as organizer-named).
     */
    String provenTextVersion(Order order) {
        String version = order.getMarketingOptInTextVersion();
        String proof = order.getMarketingOptInProof();
        if (version == null || proof == null) return null;
        if (!logic.logic().legal().organizerNamedTextVersions().contains(version)) return null;
        return namesOrganizer(order.getOrgId(), proof) ? version : null;
    }

    private boolean namesOrganizer(UUID orgId, String sentence) {
        String orgName = orgRepo.findById(orgId).map(Organization::getName).map(String::trim).orElse("");
        return !orgName.isEmpty() && fold(sentence).contains(fold(orgName));
    }

    /**
     * A paid order whose allowlisted notice named the organizer and was not refused, for a member who has not
     * opted out or objected, holds no explicit consent, and has no grant for this order yet.
     */
    private boolean softOptInEligible(Order order, Membership m) {
        // The gate also requires a live-mode order, so a test-mode record is written but never counts.
        if (!"stripe".equals(order.getPaymentMethod()) || order.getTotalMinor() <= 0) return false;
        String version = order.getMarketingNoticeTextVersion();
        String proof = order.getMarketingNoticeProof();
        if (version == null || proof == null) return false;
        if (!logic.logic().legal().softOptInNoticeVersions().contains(version)) return false;
        // Also refuses a blank proof: it can never contain the organizer's name.
        if (!namesOrganizer(order.getOrgId(), proof)) return false;
        if (optedOutCheck.isOptedOut(order.getOrgId(), m)) return false;
        if (m.isObjectedProfiling()) return false;
        // A newer unproven record would outrank the proven explicit one in ConsentGate while the flag is off.
        if ("subscribed".equals(m.getConsentStatus()) && "explicit".equals(m.getConsentBasis())) return false;
        return !consentService.hasCheckoutGrant(m.getMembershipId(), order.getId());
    }

    private void captureSoftOptIn(UUID orgId, Membership m, Order order) {
        String proof = "Paid order; shown at checkout and left the refuse box unticked: \""
                + order.getMarketingNoticeProof() + "\", order " + order.getId();
        // No origin: a soft opt-in is not the person's own fresh consent, so it never lifts an objection.
        consentService.capture(orgId, m.getMembershipId(), SOFT_OPT_IN, "checkout", proof, "email",
                order.getMarketingNoticeTextVersion(), order.getId(), null, null, null, null);
    }

    /**
     * The refuse box is the person's own objection, recorded once per refused order whatever the member's state, so
     * the permanent opt-out is written even over an operator unsubscribe or a complaint's bare objection.
     */
    private void recordRefusal(UUID orgId, Membership m, Order order) {
        if (consentRecords.existsEmailUnsubscribeFromSince(m.getMembershipId(), REFUSAL_SOURCE, order.getCreatedAt())) {
            return;
        }
        consentService.unsubscribe(orgId, m.getMembershipId(), REFUSAL_SOURCE, "email",
                ConsentOrigin.DATA_SUBJECT, null);
    }

    private static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    /**
     * Upsert Consumer then Membership, recompute projection — no phone/opt-in.
     * Package-visible so the backfill job and redeem projector can call it directly.
     * Delegates to the phone-aware form with no phone and no SMS opt-in.
     * Transactional here too: the delegation below is a self-call, so without it the backfill's
     * membership lock would be released before the row is written.
     */
    @Transactional
    public void upsertMembership(java.util.UUID orgId, String normalizedEmail, String displayName) {
        upsertMembership(orgId, normalizedEmail, displayName, null, false, false, null);
    }

    /** Phase-3 form kept for existing callers — no email opt-in captured. */
    @Transactional
    public void upsertMembership(java.util.UUID orgId, String normalizedEmail, String displayName,
                                 String phoneE164, boolean smsOptIn) {
        upsertMembership(orgId, normalizedEmail, displayName, phoneE164, smsOptIn, false, null);
    }

    /**
     * Upsert Consumer then Membership, recompute projection.
     * Package-visible so the backfill job can call it directly.
     *
     * <p>Phase 3 (§4): {@code phoneE164}/{@code smsOptIn} carry the order's SMS
     * opt-in onto the membership. Phone is projected when present; SMS consent is
     * flipped to subscribed/explicit only when the order opt-in flag is set. The
     * authoritative consent proof is written at collection time by SmsConsentService;
     * this projection is idempotent and never downgrades an existing subscription.
     */
    @Transactional
    public void upsertMembership(java.util.UUID orgId, String normalizedEmail, String displayName,
                                 String phoneE164, boolean smsOptIn,
                                 boolean emailOptIn, java.util.UUID orderIdForProof) {
        upsertMembership(orgId, normalizedEmail, displayName, phoneE164, smsOptIn,
                emailOptIn, orderIdForProof, null);
    }

    /**
     * @param proofTextOverride the verbatim sentence the buyer read next to the
     *        marketing checkbox, stored on the order (V97). Null or blank means no
     *        proof, so an email opt-in is logged and no consent is recorded.
     */
    @Transactional
    public void upsertMembership(java.util.UUID orgId, String normalizedEmail, String displayName,
                                 String phoneE164, boolean smsOptIn,
                                 boolean emailOptIn, java.util.UUID orderIdForProof,
                                 String proofTextOverride) {
        upsertMembership(orgId, normalizedEmail, displayName, phoneE164, smsOptIn,
                emailOptIn, orderIdForProof, proofTextOverride, null);
    }

    /**
     * @param textVersion the version id of that sentence (V151), stored on the consent
     *        record; null when the buyer site sent none.
     */
    @Transactional
    public void upsertMembership(java.util.UUID orgId, String normalizedEmail, String displayName,
                                 String phoneE164, boolean smsOptIn,
                                 boolean emailOptIn, java.util.UUID orderIdForProof,
                                 String proofTextOverride, String textVersion) {
        Membership m = projectMembership(orgId, normalizedEmail, displayName, phoneE164, smsOptIn);
        recordTickedOptIn(orgId, m, emailOptIn, orderIdForProof, proofTextOverride, textVersion);
    }

    /**
     * 4. Checkout email opt-in: the box is unticked by default, so a tick with the sentence the buyer read is
     * explicit consent; an opt-in without that sentence is not proof. True when the grant is (now) on record or
     * the member is unsubscribed, so the order backs nothing else.
     */
    private boolean recordTickedOptIn(UUID orgId, Membership m, boolean emailOptIn, UUID orderIdForProof,
                                      String proofTextOverride, String textVersion) {
        if ("unsubscribed".equals(m.getConsentStatus())) return true;
        if (!emailOptIn) return false;
        if (proofTextOverride == null || proofTextOverride.isBlank()) {
            log.warn("AudienceOrderProjector: opt-in without proof text for org {} order {} — no consent recorded",
                    orgId, orderIdForProof);
            return false;
        }
        // Under the membership lock, so a backfill row restoring this order cannot also miss it.
        if (orderIdForProof != null && consentService.hasCheckoutGrant(m.getMembershipId(), orderIdForProof)) {
            return true;
        }
        captureCheckoutConsent(orgId, m, orderIdForProof, proofTextOverride, textVersion, null);
        return true;
    }

    /**
     * The backfill's form: also records each checkout refusal and email grant a failed live projection lost, from
     * the order, oldest first. It never writes a soft opt-in: the next paid order captures that again.
     */
    @Transactional
    public void backfillMembership(UUID orgId, String normalizedEmail, String displayName) {
        Membership m = projectMembership(orgId, normalizedEmail, displayName, null, false);
        // The job's ledger snapshot can be stale; under the lock an erasure committed since is final.
        // This method owns its transaction (the job has none), so rollback-only rolls back quietly.
        if (erasedAddressRepo.existsPlatformWide(normalizedEmail)
                || erasedAddressRepo.existsForOrg(orgId, normalizedEmail)) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return;
        }
        Instant cutoffAt = retentionCutoff.forOrg(orgId).at();
        for (Order order : orderRepo.findByOrgIdAndNormalizedEmail(orgId, normalizedEmail)) {
            // Only the person's own sign-up since the refused order outranks it; a soft opt-in or an unconfirmed
            // sign-up never does, and a withdrawal agrees with it.
            if (order.isMarketingRefused() && !order.getCreatedAt().isBefore(cutoffAt)
                    && !consentRecords.existsOwnEmailGrantSince(m.getMembershipId(), order.getCreatedAt(),
                            ConsentGate.personSources(logic.logic().legal()))) {
                recordRefusal(orgId, m, order);
            }
            if (restorable(m, order, cutoffAt)) {
                captureCheckoutConsent(orgId, m, order.getId(), order.getMarketingOptInProof(),
                        provenTextVersion(order), order.getCreatedAt());
            }
        }
    }

    /**
     * Whether the order's checkout grant is missing and nothing the member did since, or before it, refuses it.
     * An order from before the retention cutoff is archived: its tick is never replayed.
     */
    private boolean restorable(Membership m, Order order, Instant cutoffAt) {
        if (!order.isMarketingOptIn()) return false;
        if (order.getCreatedAt().isBefore(cutoffAt)) return false;
        String proof = order.getMarketingOptInProof();
        if (proof == null || proof.isBlank()) return false;
        if (ERASE_PENDING.equals(m.getStatus())) return false;
        if ("unsubscribed".equals(m.getConsentStatus())) return false;
        if (m.isObjectedProfiling()) return false;
        if (consentService.hasCheckoutGrant(m.getMembershipId(), order.getId())) return false;
        // Unsubscribed when the order came in: the live path refused this grant on purpose.
        if (consentService.unsubscribedFromEmailAt(m.getMembershipId(), order.getCreatedAt())) return false;
        // An unsubscribe after the order wins.
        return !consentService.unsubscribedFromEmailSince(m.getMembershipId(), order.getCreatedAt());
    }

    private Membership projectMembership(UUID orgId, String normalizedEmail, String displayName,
                                         String phoneE164, boolean smsOptIn) {
        // 1. Get-or-create Consumer, race-safe.
        Consumer consumer = getOrCreateConsumer(consumerRepo, normalizedEmail, displayName);

        // 2. Get-or-create Membership the same way, then hold its row lock: a concurrent projection
        // of this buyer waits here and recomputes over both orders instead of writing over them.
        Membership m = lockOrCreateMembership(membershipRepo, orgId, consumer.getConsumerId(), displayName);

        // 2b. Project SMS phone + opt-in (§4). Never downgrade an existing subscription.
        if (phoneE164 != null && !phoneE164.isBlank()) {
            m.setPhoneE164(phoneE164);
        }
        if (smsOptIn && !"unsubscribed".equals(m.getSmsConsentStatus())) {
            m.setSmsConsentStatus("subscribed");
            m.setSmsConsentBasis("explicit");
        }

        // 3. Recompute aggregates from source (S1)
        projector.recompute(m, normalizedEmail);

        membershipRepo.save(m);
        return m;
    }

    private void captureCheckoutConsent(UUID orgId, Membership m, UUID orderId, String proofText, String textVersion,
                                        Instant occurredAt) {
        consentService.capture(orgId, m.getMembershipId(), "explicit", "checkout",
                "Ticked the marketing opt-in at checkout next to: \"" + proofText + "\""
                        + (orderId != null ? ", order " + orderId : ""),
                "email", textVersion, orderId, null, ConsentOrigin.DATA_SUBJECT, null, occurredAt);
    }
}

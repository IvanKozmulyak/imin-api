package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
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
 * <p>S5: an email-consent row is written ONLY from an order's own ticked opt-in with the
 * sentence the buyer read (basis 'explicit'); without both, no basis and the Send Gate excludes them.
 * <p>S1: aggregates are DERIVED from source rows (not incremented).
 */
@Component
public class AudienceOrderProjector {

    private static final Logger log = LoggerFactory.getLogger(AudienceOrderProjector.class);

    private final OrderRepository orderRepo;
    private final ConsumerRepository consumerRepo;
    private final MembershipRepository membershipRepo;
    private final MembershipProjector projector;
    private final ConsentService consentService;
    private final ApplicationEventPublisher events;
    private final OrganizationRepository orgRepo;
    private final AudiencePlanLogic logic;
    private final TransactionTemplate requiresNew;

    public AudienceOrderProjector(OrderRepository orderRepo,
                                   ConsumerRepository consumerRepo,
                                   MembershipRepository membershipRepo,
                                   MembershipProjector projector,
                                   ConsentService consentService,
                                   ApplicationEventPublisher events,
                                   OrganizationRepository orgRepo,
                                   AudiencePlanLogic logic,
                                   PlatformTransactionManager transactionManager) {
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
        upsertMembership(order.getOrgId(), normalizedEmail, order.getEmail(),
                order.getBuyerPhone(), order.isSmsMarketingOptIn(),
                order.isMarketingOptIn(), order.getId(), order.getMarketingOptInProof(),
                provenTextVersion(order));
        // Downstream projections read the membership, so they follow this commit, not the order's.
        events.publishEvent(new MembershipProjected(order.getOrgId(), normalizedEmail));
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
        String orgName = orgRepo.findById(order.getOrgId()).map(Organization::getName).map(String::trim).orElse("");
        if (orgName.isEmpty()) return null;
        return fold(proof).contains(fold(orgName)) ? version : null;
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

        // 4. Checkout email opt-in: the box is unticked by default, so a tick with the
        // sentence the buyer read is explicit consent; an opt-in without that sentence is not proof.
        if (!emailOptIn || "unsubscribed".equals(m.getConsentStatus())) {
            return;
        }
        if (proofTextOverride == null || proofTextOverride.isBlank()) {
            log.warn("AudienceOrderProjector: opt-in without proof text for org {} order {} — no consent recorded",
                    orgId, orderIdForProof);
            return;
        }
        consentService.capture(orgId, m.getMembershipId(), "explicit", "checkout",
                "Ticked the marketing opt-in at checkout next to: \"" + proofTextOverride + "\""
                        + (orderIdForProof != null ? ", order " + orderIdForProof : ""),
                "email", textVersion, orderIdForProof, ConsentOrigin.DATA_SUBJECT, null);
    }
}

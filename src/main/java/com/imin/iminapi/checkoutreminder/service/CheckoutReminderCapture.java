package com.imin.iminapi.checkoutreminder.service;

import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.checkoutreminder.config.CheckoutReminderProperties;
import com.imin.iminapi.checkoutreminder.repository.CheckoutReminderRepository;
import com.imin.iminapi.checkoutreminder.repository.OrgCheckoutReminderSettingRepository;
import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.model.CheckoutConsent;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.stripe.StripeProperties;
import com.imin.iminapi.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps an expired, unpaid checkout for one reminder — only when the buyer ticked the organizer-named
 * marketing box and the sentence they read is on record. Anything else stores nothing.
 *
 * <p>Runs after the expiry webhook committed the seat release, in its own transaction, and never
 * throws: a reminder that could not be stored must not hold seats or fail the webhook.
 */
@Component
public class CheckoutReminderCapture {

    private static final Logger log = LoggerFactory.getLogger(CheckoutReminderCapture.class);
    /** Same bound as the checkout request; a larger qty in metadata is not a checkout we made. */
    private static final int MAX_QTY = 10;

    private final CheckoutReminderProperties props;
    private final CheckoutReminderRepository reminders;
    private final OrgCheckoutReminderSettingRepository settings;
    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final OrganizationRepository orgs;
    private final AudiencePlanLogic logic;
    private final StripeProperties stripeProps;
    private final Clock clock;
    private final TransactionTemplate requiresNew;

    public CheckoutReminderCapture(CheckoutReminderProperties props, CheckoutReminderRepository reminders,
                                   OrgCheckoutReminderSettingRepository settings, EventRepository events,
                                   TicketTierRepository tiers, OrganizationRepository orgs,
                                   AudiencePlanLogic logic, StripeProperties stripeProps, Clock clock,
                                   PlatformTransactionManager transactionManager) {
        this.props = props;
        this.reminders = reminders;
        this.settings = settings;
        this.events = events;
        this.tiers = tiers;
        this.orgs = orgs;
        this.logic = logic;
        this.stripeProps = stripeProps;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionExpired(CheckoutSessionExpired expired) {
        try {
            requiresNew.executeWithoutResult(status -> capture(expired));
        } catch (Exception e) {
            // No throwable: its SQL detail carries the address past the redaction.
            log.error("[checkout-reminder] capture failed for session {}: {}: {}", expired.sessionId(),
                    e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()));
        }
    }

    private void capture(CheckoutSessionExpired expired) {
        if (!props.isEnabled()) return;
        Map<String, String> md = expired.metadata();
        if (!"web".equals(md.get("client"))) return;
        if (!"true".equals(md.get("marketing_opt_in"))) return;
        CheckoutConsent consent = CheckoutConsent.fromMetadata(md);
        String proof = consent.marketingOptInProofText();
        String version = consent.marketingOptInTextVersion();
        // CheckoutConsent drops a version that came without its sentence, so this also requires the sentence.
        if (version == null || !logic.logic().legal().organizerNamedTextVersions().contains(version)) return;
        String email = EmailNormalizer.normalize(firstNonBlank(expired.customerEmail(), md.get("buyer_email")));
        if (email == null || email.isBlank() || expired.sessionId() == null) return;

        UUID eventId = uuid(md.get("event_id"));
        UUID tierId = uuid(md.get("tier_id"));
        Integer qty = positiveInt(md.get("qty"));
        if (eventId == null || tierId == null || qty == null || qty > MAX_QTY) return;
        Event event = events.findById(eventId).orElse(null);
        if (event == null) return;
        TicketTier tier = tiers.findById(tierId).orElse(null);
        if (tier == null || !eventId.equals(tier.getEventId())) return;
        Organization org = orgs.findById(event.getOrgId()).orElse(null);
        if (org == null || !namesOrganizer(proof, org)) return;
        if (!settings.isEnabled(org.getId())) return;

        Instant now = clock.instant();
        Instant startedAt = expired.created() != null
                ? Instant.ofEpochSecond(expired.created())
                : now.minus(Duration.ofMinutes(stripeProps.getCheckoutSessionTtlMinutes()));
        Instant dueAt = CheckoutReminderSchedule.dueAt(startedAt, org.getTimezone(), props.delay());
        int written = reminders.upsertWaiting(UUID.randomUUID(), org.getId(), eventId, tierId, qty,
                expired.sessionId(), email, EmailLocale.normalizeOrNull(md.get("buyer_locale")),
                md.get("promo_id") != null, proof, version, startedAt, now, dueAt);
        log.info("[checkout-reminder] session {} on event {} {}", expired.sessionId(), eventId,
                written == 0 ? "left the existing row as it was" : "kept for a reminder");
    }

    /** The ticked sentence must name this organizer, as for an order's checkout consent. */
    private static boolean namesOrganizer(String proof, Organization org) {
        String name = org.getName() == null ? "" : org.getName().trim();
        return proof != null && !name.isEmpty() && fold(proof).contains(fold(name));
    }

    private static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static UUID uuid(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Integer positiveInt(String raw) {
        if (raw == null) return null;
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

package com.imin.iminapi.checkoutreminder.service;

import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.audience.service.StickyOptOutWriter;
import com.imin.iminapi.audience.service.SuppressionService;
import com.imin.iminapi.checkoutreminder.model.CheckoutReminder;
import com.imin.iminapi.checkoutreminder.repository.CheckoutReminderRepository;
import com.imin.iminapi.marketing.model.ProviderEvent;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Resend delivery events for reminder emails. A permanent bounce lists the address as undeliverable
 * for everyone; a spam complaint is this person objecting to this organizer, so it writes the sticky
 * opt-out and, when they are in the organizer's audience, a marketing suppression; a failed opt-out
 * write throws, so the webhook answers 500 and Resend retries. A transient bounce writes nothing shared.
 */
@Service
public class CheckoutReminderDeliveryProjector {

    private static final Logger log = LoggerFactory.getLogger(CheckoutReminderDeliveryProjector.class);

    private final CheckoutReminderRepository reminders;
    private final SuppressionService suppressions;
    private final StickyOptOutWriter stickyOptOuts;
    private final ConsumerRepository consumers;
    private final MembershipRepository memberships;

    public CheckoutReminderDeliveryProjector(CheckoutReminderRepository reminders, SuppressionService suppressions,
                                             StickyOptOutWriter stickyOptOuts, ConsumerRepository consumers,
                                             MembershipRepository memberships) {
        this.reminders = reminders;
        this.suppressions = suppressions;
        this.stickyOptOuts = stickyOptOuts;
        this.consumers = consumers;
        this.memberships = memberships;
    }

    /** The reminder behind {@code providerMessageId}, if any. */
    public Optional<CheckoutReminder> find(String providerMessageId) {
        return providerMessageId == null ? Optional.empty() : reminders.findByProviderMessageId(providerMessageId);
    }

    @Transactional
    public void project(CheckoutReminder r, String toEmail, String type, String bounceType, Instant occurredAt) {
        if (type == null) return;
        String email = EmailNormalizer.normalize(toEmail != null && !toEmail.isBlank() ? toEmail : r.getEmailNormalized());
        switch (type) {
            case ProviderEvent.TYPE_DELIVERED -> reminders.markDelivered(r.getId(), occurredAt);
            case ProviderEvent.TYPE_BOUNCED -> {
                if (isPermanent(bounceType) && email != null && !email.isBlank()) {
                    suppressions.addDeliverabilityIfAbsent(email, "hard-bounce");
                }
            }
            case ProviderEvent.TYPE_COMPLAINED -> {
                if (email != null && !email.isBlank()) {
                    stickyOptOuts.recordOrFail(email, r.getOrgId(), "complaint");
                    memberIn(r.getOrgId(), email).ifPresent(membershipId -> suppressions.addMarketingIfAbsent(
                            r.getOrgId(), membershipId, "spam",
                            new AuthPrincipal(null, r.getOrgId(), UserRole.MEMBER, null)));
                }
                reminders.markComplained(r.getId(), occurredAt);
            }
            default -> log.info("[checkout-reminder] ignored provider event {} for reminder {}", type, r.getId());
        }
    }

    private Optional<UUID> memberIn(UUID orgId, String email) {
        return consumers.findByNormalizedEmail(email)
                .flatMap(c -> memberships.findByOrgIdAndConsumerId(orgId, c.getConsumerId()))
                .map(m -> m.getMembershipId());
    }

    /** Resend's own classification; anything not recognised is not permanent. */
    private static boolean isPermanent(String bounceType) {
        return bounceType != null && ("permanent".equalsIgnoreCase(bounceType.trim())
                || "hard".equalsIgnoreCase(bounceType.trim()));
    }
}

package com.imin.iminapi.service.audience;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.dto.publicapi.SmsConsentRequest;
import com.imin.iminapi.dto.publicapi.SmsConsentResponse;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Records the post-purchase SMS marketing opt-in from the order-confirmation
 * page (spec §4). Keyed by the order token (the order never exposes the event
 * UUID). SMS consent is explicit-only (§7): only a ticked checkbox with a valid
 * E.164 phone produces a consent proof row.
 *
 * <p>A wrong/forged token returns the standard leak-safe 404 NOT_FOUND envelope
 * (same as the order-detail read).
 */
@Service
public class SmsConsentService {

    private static final int PROOF_MAX_LENGTH = 500;

    private final OrderRepository orders;
    private final ConsumerRepository consumers;
    private final MembershipRepository memberships;
    private final ConsentService consentService;

    public SmsConsentService(OrderRepository orders,
                             ConsumerRepository consumers,
                             MembershipRepository memberships,
                             ConsentService consentService) {
        this.orders = orders;
        this.consumers = consumers;
        this.memberships = memberships;
        this.consentService = consentService;
    }

    @Transactional
    public SmsConsentResponse submit(String token, SmsConsentRequest request) {
        // Leak-safe: unknown/forged token 404s exactly like the order read.
        Order order = orders.findByToken(token).orElseThrow(() -> ApiException.notFound("Order"));

        boolean optIn = request != null && request.optIn();

        // Unchecked (or null body): record nothing. If a phone was provided we
        // still stamp it for the organizer's visibility, but write no consent.
        if (!optIn) {
            if (request != null && request.phone() != null && !request.phone().isBlank()) {
                PhoneNormalizer.normalize(request.phone()).ifPresent(order::setBuyerPhone);
                orders.save(order);
            }
            return SmsConsentResponse.declined();
        }

        // Opt-in true → phone required and must validate to E.164.
        String phone = PhoneNormalizer.normalize(request.phone()).orElseThrow(() -> {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("phone", "must be a valid phone number in international format (e.g. +380671234567)");
            return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Invalid request body", fields);
        });

        String proofText = request.proofText() == null ? null
                : request.proofText().substring(0, Math.min(request.proofText().length(), PROOF_MAX_LENGTH));

        // Stamp order-level snapshot.
        order.setBuyerPhone(phone);
        order.setSmsMarketingOptIn(true);
        orders.save(order);

        // Upsert the membership synchronously so consent attaches even if the
        // async AudienceOrderProjector hasn't run for this order yet.
        UUID membershipId = upsertMembership(order.getOrgId(), order.getEmail(), phone);

        // Write the channel='sms', explicit, order_confirmation proof row + SMS state.
        consentService.capture(order.getOrgId(), membershipId, "explicit",
                "order_confirmation", proofText, "sms", null);

        return SmsConsentResponse.ok();
    }

    /**
     * INSERT-first Consumer + Membership upsert, mirroring
     * {@code AudienceOrderProjector.upsertMembership}. Sets phone_e164 on the
     * membership; the full aggregate recompute is left to the projector.
     */
    private UUID upsertMembership(UUID orgId, String rawEmail, String phone) {
        String normalizedEmail = EmailNormalizer.normalize(rawEmail);

        Consumer consumer = consumers.findByNormalizedEmail(normalizedEmail).orElse(null);
        if (consumer == null) {
            Consumer newC = new Consumer();
            newC.setNormalizedEmail(normalizedEmail);
            newC.setDisplayName(rawEmail);
            try {
                consumer = consumers.save(newC);
            } catch (DataIntegrityViolationException dup) {
                consumer = consumers.findByNormalizedEmail(normalizedEmail)
                        .orElseThrow(() -> new IllegalStateException(
                                "Consumer insert race but still not found: " + normalizedEmail));
            }
        }

        // `consumer` is reassigned above, so it is not effectively final and cannot
        // be captured by an orElseGet lambda; use an if/else like the projector.
        Optional<Membership> existing =
                memberships.findByOrgIdAndConsumerId(orgId, consumer.getConsumerId());
        Membership m;
        if (existing.isPresent()) {
            m = existing.get();
        } else {
            m = new Membership();
            m.setOrgId(orgId);
            m.setConsumerId(consumer.getConsumerId());
            m.setDisplayName(rawEmail);
        }
        m.setPhoneE164(phone);
        return memberships.save(m).getMembershipId();
    }
}

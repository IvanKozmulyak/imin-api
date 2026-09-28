package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.dto.ConsentConfirmationResponse;
import com.imin.iminapi.audience.model.ConsentConfirmationToken;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.MarketingOptOutId;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentConfirmationTokenRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.marketing.render.OrganizerIdentity;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Double opt-in for door QR and survey sign-ups: stores one confirmation email per sign-up (at most one a day per
 * member) and turns a valid link into confirmed consent. Every failure is the same neutral {@code invalid}.
 */
@Service
public class ConsentConfirmationService {

    static final Duration TTL = Duration.ofDays(7);
    static final Duration RESEND_AFTER = Duration.ofHours(24);

    private final ConsentConfirmationTokenRepository tokens;
    private final ConsentConfirmationTokenSigner signer;
    private final MembershipRepository memberships;
    private final ConsumerRepository consumers;
    private final ErasedAddressRepository erased;
    private final MarketingOptOutRepository optOuts;
    private final SuppressionRepository suppressions;
    private final OrganizationRepository orgs;
    private final ConsentService consentService;
    private final AudiencePlanAccess access;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ConsentConfirmationService(ConsentConfirmationTokenRepository tokens, ConsentConfirmationTokenSigner signer,
                                      MembershipRepository memberships, ConsumerRepository consumers,
                                      ErasedAddressRepository erased, MarketingOptOutRepository optOuts,
                                      SuppressionRepository suppressions, OrganizationRepository orgs,
                                      ConsentService consentService, AudiencePlanAccess access,
                                      ApplicationEventPublisher events, Clock clock) {
        this.tokens = tokens;
        this.signer = signer;
        this.memberships = memberships;
        this.consumers = consumers;
        this.erased = erased;
        this.optOuts = optOuts;
        this.suppressions = suppressions;
        this.orgs = orgs;
        this.consentService = consentService;
        this.access = access;
        this.events = events;
        this.clock = clock;
    }

    /**
     * After a door QR / survey capture: stores the email row and queues the send for after commit.
     * Nothing while the flag is off or when this member was mailed in the last 24 h.
     */
    @Transactional
    public void requestFor(UUID orgId, UUID membershipId, UUID consentRecordId, String locale) {
        if (!access.consentConfirmationEmailsEnabled()) return;
        Instant now = clock.instant();
        Optional<ConsentConfirmationToken> last = tokens.findFirstByMembershipIdOrderBySentAtDesc(membershipId);
        if (last.isPresent() && last.get().getSentAt().isAfter(now.minus(RESEND_AFTER))) return;

        ConsentConfirmationToken t = new ConsentConfirmationToken();
        t.setOrgId(orgId);
        t.setMembershipId(membershipId);
        t.setConsentRecordId(consentRecordId);
        t.setLocale(EmailLocale.normalize(locale));
        t.setSentAt(now);
        t.setExpiresAt(now.plus(TTL));
        tokens.save(t);
        events.publishEvent(new ConsentConfirmationRequested(t.getId()));
    }

    /** Read-only: whether the link would confirm. Mail scanners fetch links, so GET never changes anything. */
    @Transactional(readOnly = true)
    public ConsentConfirmationResponse preview(String rawToken) {
        Instant now = clock.instant();
        return usable(rawToken, now)
                .filter(u -> !optedOut(u.row().getOrgId(), u.membership()))
                .map(u -> new ConsentConfirmationResponse(ConsentConfirmationResponse.PENDING, u.organizerName()))
                .orElseGet(ConsentConfirmationResponse::invalid);
    }

    /**
     * Burns the token, then confirms the member's pending sign-ups unless the address has since opted out, been
     * suppressed or erased: confirming never brings back a withdrawn consent.
     */
    @Transactional
    public ConsentConfirmationResponse confirm(String rawToken) {
        Instant now = clock.instant();
        Optional<Usable> found = usable(rawToken, now);
        if (found.isEmpty()) return ConsentConfirmationResponse.invalid();
        Usable u = found.get();
        if (tokens.markUsed(u.row().getId(), now) != 1) return ConsentConfirmationResponse.invalid();
        UUID orgId = u.row().getOrgId();
        if (optedOut(orgId, u.membership())) return ConsentConfirmationResponse.invalid();
        consentService.confirmPending(orgId, u.membership().getMembershipId(), now);
        return new ConsentConfirmationResponse(ConsentConfirmationResponse.CONFIRMED, u.organizerName());
    }

    /** The organizer as the accepted consent sentence names it (door/survey check {@code getName()}), single line. */
    public static String organizerName(Organization org) {
        return OrganizerIdentity.singleLine(org.getName());
    }

    private Optional<Usable> usable(String rawToken, Instant now) {
        return signer.verify(rawToken)
                .flatMap(tokens::findById)
                .filter(t -> t.getUsedAt() == null && now.isBefore(t.getExpiresAt()))
                .filter(t -> access.isEnabled(t.getOrgId()))
                .flatMap(t -> memberships.findByIdAndOrgId(t.getMembershipId(), t.getOrgId())
                        .flatMap(m -> orgs.findById(t.getOrgId())
                                .map(o -> new Usable(t, m, organizerName(o)))));
    }

    private boolean optedOut(UUID orgId, Membership m) {
        if (!"active".equals(m.getStatus()) || "unsubscribed".equals(m.getConsentStatus())) return true;
        if (suppressions.findMarketingByOrgAndMembership(orgId, m.getMembershipId()).isPresent()) return true;
        String email = consumers.findByConsumerId(m.getConsumerId()).map(Consumer::getNormalizedEmail).orElse(null);
        if (email == null) return true;
        return erased.existsForOrg(orgId, email) || erased.existsPlatformWide(email)
                || optOuts.existsById(new MarketingOptOutId(email, orgId, "email"));
    }

    private record Usable(ConsentConfirmationToken row, Membership membership, String organizerName) {}
}

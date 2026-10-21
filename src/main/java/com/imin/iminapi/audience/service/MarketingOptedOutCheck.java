package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.MarketingOptOutId;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The one answer to "has this person opted out of this organizer's email marketing": membership
 * not active or unsubscribed, a marketing suppression, an erased address, or a permanent opt-out.
 */
@Component
public class MarketingOptedOutCheck {

    private final MembershipRepository memberships;
    private final ConsumerRepository consumers;
    private final ErasedAddressRepository erased;
    private final MarketingOptOutRepository optOuts;
    private final SuppressionRepository suppressions;

    public MarketingOptedOutCheck(MembershipRepository memberships, ConsumerRepository consumers,
                                  ErasedAddressRepository erased, MarketingOptOutRepository optOuts,
                                  SuppressionRepository suppressions) {
        this.memberships = memberships;
        this.consumers = consumers;
        this.erased = erased;
        this.optOuts = optOuts;
        this.suppressions = suppressions;
    }

    /** For a known membership of the organizer. */
    public boolean isOptedOut(UUID orgId, Membership m) {
        if (!"active".equals(m.getStatus()) || "unsubscribed".equals(m.getConsentStatus())) return true;
        if (suppressions.findMarketingByOrgAndMembership(orgId, m.getMembershipId()).isPresent()) return true;
        String email = consumers.findByConsumerId(m.getConsumerId()).map(Consumer::getNormalizedEmail).orElse(null);
        if (email == null) return true;
        return addressOptedOut(orgId, email);
    }

    /**
     * For a buyer known only by address. With no membership yet only the address-level
     * rows (erased, permanent opt-out) can apply.
     */
    public boolean isOptedOut(UUID orgId, String emailNormalized) {
        var membership = consumers.findByNormalizedEmail(emailNormalized)
                .flatMap(c -> memberships.findByOrgIdAndConsumerId(orgId, c.getConsumerId()));
        if (membership.isPresent()) return isOptedOut(orgId, membership.get());
        return addressOptedOut(orgId, emailNormalized);
    }

    private boolean addressOptedOut(UUID orgId, String email) {
        return erased.existsForOrg(orgId, email) || erased.existsPlatformWide(email)
                || optOuts.existsById(new MarketingOptOutId(email, orgId, "email"));
    }
}

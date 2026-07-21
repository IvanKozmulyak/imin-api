package com.imin.iminapi.marketing.sms;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.MembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Decides whether a phone number may receive MARKETING SMS (spec §7, GDPR Art.6/21).
 *
 * <p>Consent is a property of the phone, not of one org's list, because imin sends
 * from a single shared alphanumeric sender ID. A number is marketable iff:
 * <ul>
 *   <li>at least one membership on that number is {@code sms_consent_status=subscribed}
 *       with {@code sms_consent_basis=explicit} (SMS accepts explicit ONLY — no soft opt-in), AND</li>
 *   <li>no membership on that number is {@code sms_consent_status=unsubscribed}
 *       (a STOP on any org suppresses the number globally — see the inbound STOP webhook).</li>
 * </ul>
 * "Unsubscribed anywhere wins" is deliberately strict: on a shared sender we must
 * never re-message someone who has said STOP, even if a different org still shows them subscribed.
 */
@Service
public class SmsConsentGate {

    private final MembershipRepository memberships;

    public SmsConsentGate(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public boolean canSendMarketing(String phoneE164) {
        if (phoneE164 == null || phoneE164.isBlank()) return false;
        List<Membership> onNumber = memberships.findAllByPhoneE164(phoneE164);
        if (onNumber.isEmpty()) return false;

        boolean anyExplicitSubscribed = false;
        for (Membership m : onNumber) {
            if ("unsubscribed".equals(m.getSmsConsentStatus())) {
                return false; // global opt-out wins
            }
            if ("subscribed".equals(m.getSmsConsentStatus())
                    && "explicit".equals(m.getSmsConsentBasis())) {
                anyExplicitSubscribed = true;
            }
        }
        return anyExplicitSubscribed;
    }
}

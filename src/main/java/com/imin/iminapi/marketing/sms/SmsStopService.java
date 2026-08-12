package com.imin.iminapi.marketing.sms;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Applies an inbound STOP to a phone number (GDPR Art.21 / France mandatory opt-out).
 *
 * <p>imin sends from ONE shared alphanumeric sender ID, so a STOP is global to the
 * number: every membership carrying it (across all orgs) is unsubscribed on the SMS
 * channel. Reuses the existing channel-aware {@link ConsentService#unsubscribe} — the
 * same synchronous, always-succeeds path the owned opt-out link uses — so the SMS
 * suppression state and its immutable proof row are written exactly once, one way.
 */
@Service
public class SmsStopService {

    private static final Logger log = LoggerFactory.getLogger(SmsStopService.class);

    private final MembershipRepository memberships;
    private final ConsentService consentService;

    public SmsStopService(MembershipRepository memberships, ConsentService consentService) {
        this.memberships = memberships;
        this.consentService = consentService;
    }

    /**
     * Unsubscribe every membership on {@code phoneE164} from SMS.
     *
     * @return number of memberships suppressed (0 if the number is unknown — still a valid STOP)
     */
    @Transactional
    public int suppressPhone(String phoneE164, String source) {
        if (phoneE164 == null || phoneE164.isBlank()) return 0;
        List<Membership> onNumber = memberships.findAllByPhoneE164(phoneE164);
        for (Membership m : onNumber) {
            // channel="sms", principal=null (system-initiated, no organizer actor).
            // DATA_SUBJECT: the person texted STOP. `source` here is passed through from
            // the inbound webhook and is exactly why stickiness cannot be inferred from
            // it — an allow-list over source strings would silently drop this legally
            // mandated path (§16 / ConsentOrigin).
            consentService.unsubscribe(m.getOrgId(), m.getMembershipId(), source, "sms",
                    ConsentOrigin.DATA_SUBJECT, null);
        }
        log.info("[sms-stop] source={} suppressed {} membership(s)", source, onNumber.size());
        return onNumber.size();
    }
}

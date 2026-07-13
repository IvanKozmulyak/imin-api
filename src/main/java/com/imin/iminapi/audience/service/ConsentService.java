package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Consent capture and unsubscribe.
 *
 * <p>M3: every mutation:
 * <ol>
 *   <li>Appends an immutable {@link ConsentRecord} row.</li>
 *   <li>Updates the denormalized {@code consent_status} + {@code consent_basis} on the
 *       membership so the send gate reads the CURRENT state from one column, not from
 *       "is there a row with status=unsubscribed".</li>
 * </ol>
 * Unsubscribe is SYNCHRONOUS and always succeeds immediately (Art.21 / objection).
 */
@Service
public class ConsentService {

    private final MembershipRepository membershipRepo;
    private final ConsentRecordRepository consentRepo;
    private final AuditLogger auditLogger;

    public ConsentService(MembershipRepository membershipRepo,
                          ConsentRecordRepository consentRepo,
                          AuditLogger auditLogger) {
        this.membershipRepo = membershipRepo;
        this.consentRepo = consentRepo;
        this.auditLogger = auditLogger;
    }

    /**
     * Capture an explicit or soft-opt-in consent event on the EMAIL channel.
     * Kept for existing callers; delegates to the channel-aware overload.
     */
    @Transactional
    public void capture(UUID orgId, UUID membershipId, String basis, String source,
                        String proofText, AuthPrincipal principal) {
        capture(orgId, membershipId, basis, source, proofText, "email", principal);
    }

    /**
     * Capture an explicit or soft-opt-in consent event on the given channel
     * (email | sms). Appends an immutable proof row and updates the membership's
     * denormalized per-channel state. SMS accepts 'explicit' only (§7); the SMS
     * columns are separate from the email consent_status/consent_basis pair, so
     * an SMS capture never touches email consent and vice-versa.
     */
    @Transactional
    public void capture(UUID orgId, UUID membershipId, String basis, String source,
                        String proofText, String channel, AuthPrincipal principal) {
        Membership m = requireMembership(orgId, membershipId);

        ConsentRecord r = new ConsentRecord();
        r.setMembershipId(membershipId);
        r.setChannel(channel);
        r.setStatus("subscribed");
        r.setLawfulBasis(basis);
        r.setSource(source);
        r.setProofText(proofText);
        consentRepo.save(r);

        // M3: denormalize current state onto membership, per channel.
        if ("sms".equals(channel)) {
            m.setSmsConsentStatus("subscribed");
            m.setSmsConsentBasis(basis);
        } else {
            m.setConsentStatus("subscribed");
            m.setConsentBasis(basis);
        }
        membershipRepo.save(m);

        // Public, unauthenticated captures (e.g. the SMS order-confirmation opt-in, §4)
        // have no organizer actor; the audit row requires a non-null org/actor, so skip
        // it. The consent proof row + membership state above are the authoritative record.
        if (principal != null) {
            auditLogger.record(principal, AuditActions.CONSENT_CAPTURED, "membership",
                    membershipId, "Consent captured: channel=" + channel + " basis=" + basis + " source=" + source);
        }
    }

    /**
     * Unsubscribe on the EMAIL channel. Kept for existing callers; delegates.
     */
    @Transactional
    public void unsubscribe(UUID orgId, UUID membershipId, String source, AuthPrincipal principal) {
        unsubscribe(orgId, membershipId, source, "email", principal);
    }

    /**
     * Unsubscribe on the given channel — synchronous, always succeeds (Art.21).
     * Appends a proof row and clears the per-channel denormalized state.
     */
    @Transactional
    public void unsubscribe(UUID orgId, UUID membershipId, String source, String channel,
                            AuthPrincipal principal) {
        Membership m = requireMembership(orgId, membershipId);

        ConsentRecord r = new ConsentRecord();
        r.setMembershipId(membershipId);
        r.setChannel(channel);
        r.setStatus("unsubscribed");
        r.setLawfulBasis(null);
        r.setSource(source);
        consentRepo.save(r);

        if ("sms".equals(channel)) {
            m.setSmsConsentStatus("unsubscribed");
            m.setSmsConsentBasis(null);
        } else {
            m.setConsentStatus("unsubscribed");
            m.setConsentBasis(null);
        }
        membershipRepo.save(m);

        // As in capture(): skip the organizer-actor audit row when there is no
        // authenticated principal (public/system-initiated unsubscribe).
        if (principal != null) {
            auditLogger.record(principal, AuditActions.CONSENT_UNSUBSCRIBED, "membership",
                    membershipId, "Unsubscribed via " + source + " channel=" + channel);
        }
    }

    private Membership requireMembership(UUID orgId, UUID membershipId) {
        return membershipRepo.findByIdAndOrgId(membershipId, orgId)
                .orElseThrow(() -> ApiException.notFound("Membership"));
    }
}

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
     * Capture an explicit or soft-opt-in consent event.
     * Appends an immutable proof row and updates the membership's denormalized state.
     */
    @Transactional
    public void capture(UUID orgId, UUID membershipId, String basis, String source,
                        String proofText, AuthPrincipal principal) {
        Membership m = requireMembership(orgId, membershipId);

        ConsentRecord r = new ConsentRecord();
        r.setMembershipId(membershipId);
        r.setChannel("email");
        r.setStatus("subscribed");
        r.setLawfulBasis(basis);
        r.setSource(source);
        r.setProofText(proofText);
        consentRepo.save(r);

        // M3: denormalize current state onto membership
        m.setConsentStatus("subscribed");
        m.setConsentBasis(basis);
        membershipRepo.save(m);

        auditLogger.record(principal, AuditActions.CONSENT_CAPTURED, "membership",
                membershipId, "Consent captured: basis=" + basis + " source=" + source);
    }

    /**
     * Unsubscribe — synchronous, always succeeds immediately (Art.21).
     * Appends proof row, sets consent_status='unsubscribed', clears basis.
     */
    @Transactional
    public void unsubscribe(UUID orgId, UUID membershipId, String source, AuthPrincipal principal) {
        Membership m = requireMembership(orgId, membershipId);

        ConsentRecord r = new ConsentRecord();
        r.setMembershipId(membershipId);
        r.setChannel("email");
        r.setStatus("unsubscribed");
        r.setLawfulBasis(null);
        r.setSource(source);
        consentRepo.save(r);

        // M3: denormalize — gate reads this column directly
        m.setConsentStatus("unsubscribed");
        m.setConsentBasis(null);
        membershipRepo.save(m);

        auditLogger.record(principal, AuditActions.CONSENT_UNSUBSCRIBED, "membership",
                membershipId, "Unsubscribed via " + source);
    }

    private Membership requireMembership(UUID orgId, UUID membershipId) {
        return membershipRepo.findByIdAndOrgId(membershipId, orgId)
                .orElseThrow(() -> ApiException.notFound("Membership"));
    }
}

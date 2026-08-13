package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.ConsentRecord;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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

    private static final Logger log = LoggerFactory.getLogger(ConsentService.class);

    private final MembershipRepository membershipRepo;
    private final ConsentRecordRepository consentRepo;
    private final ConsumerRepository consumerRepo;
    private final MarketingOptOutRecorder optOutRecorder;
    private final AuditLogger auditLogger;

    public ConsentService(MembershipRepository membershipRepo,
                          ConsentRecordRepository consentRepo,
                          ConsumerRepository consumerRepo,
                          MarketingOptOutRecorder optOutRecorder,
                          AuditLogger auditLogger) {
        this.membershipRepo = membershipRepo;
        this.consentRepo = consentRepo;
        this.consumerRepo = consumerRepo;
        this.optOutRecorder = optOutRecorder;
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
    public void unsubscribe(UUID orgId, UUID membershipId, String source,
                            ConsentOrigin origin, AuthPrincipal principal) {
        unsubscribe(orgId, membershipId, source, "email", origin, principal);
    }

    /**
     * Unsubscribe on the given channel — synchronous, always succeeds (Art.21).
     * Appends a proof row and clears the per-channel denormalized state.
     *
     * <p>{@code origin} says who is really acting and is the sole input to the sticky
     * {@code marketing_optouts} write (§6.3) — see {@link ConsentOrigin} for why that
     * cannot be inferred from {@code source}. {@code source} is unchanged: it stays the
     * human-readable audit string on {@code consent_records}.
     *
     * <p>Anything other than {@link ConsentOrigin#DATA_SUBJECT}, {@code null} included,
     * writes no sticky row. That is the safe direction to fail in: a missing sticky row
     * costs a re-subscribe the buyer can undo, while a spurious one silently bars them
     * from ever hearing from that organizer again.
     */
    @Transactional
    public void unsubscribe(UUID orgId, UUID membershipId, String source, String channel,
                            ConsentOrigin origin, AuthPrincipal principal) {
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

        // DATA_SUBJECT alone is sticky. OPERATOR is an organizer tidying their own list,
        // and DATA_SUBJECT_GLOBAL is the buyer's own master switch — reversible tomorrow,
        // so it must not leave permanent per-organizer objections behind it.
        if (origin == ConsentOrigin.DATA_SUBJECT) {
            recordStickyOptOut(m, orgId, channel, source);
        } else if (origin == null) {
            // Unreachable today — all call sites pass a compile-time constant. Kept
            // audible rather than silent: a future caller that forgets would otherwise
            // lose sticky rows for years without a single line in the log.
            log.warn("Unsubscribe with no ConsentOrigin — no sticky opt-out recorded. membership={} source={}",
                    membershipId, source);
        }

        // As in capture(): skip the organizer-actor audit row when there is no
        // authenticated principal (public/system-initiated unsubscribe).
        if (principal != null) {
            auditLogger.record(principal, AuditActions.CONSENT_UNSUBSCRIBED, "membership",
                    membershipId, "Unsubscribed via " + source + " channel=" + channel);
        }
    }

    /**
     * Best-effort sticky {@code marketing_optouts} write — the record that stops a
     * Release-2 preference-centre toggle from resurrecting an organizer the buyer
     * explicitly left (§6.3).
     *
     * <p><b>It can never fail the unsubscribe.</b> This is the live RFC 8058 one-click
     * path and the legally mandated SMS STOP path; recording stickiness is a bonus
     * record, never a precondition. Two things make that true together, and neither is
     * sufficient alone:
     * <ul>
     *   <li>{@link MarketingOptOutRecorder#record} is {@code REQUIRES_NEW} on a separate
     *       bean, so a constraint violation rolls back only its own transaction and
     *       never marks this one rollback-only. A plain try/catch around a same-transaction
     *       write would still explode at commit with an {@code UnexpectedRollbackException}.</li>
     *   <li>The catch is here, outside that boundary, so it also covers whatever the
     *       proxy throws at the inner commit.</li>
     * </ul>
     *
     * <p>The email is resolved here rather than inside the recorder on purpose: the
     * {@code Consumer} row may have been written by this very transaction and would be
     * invisible to the recorder's separate connection.
     */
    private void recordStickyOptOut(Membership m, UUID orgId, String channel, String source) {
        // Deliberately OUTSIDE the try. This read runs in the CALLER's transaction, not
        // the recorder's, so a JDBC-level failure here marks the caller rollback-only —
        // swallowing it would hide the cause and still kill the unsubscribe at commit.
        // Its failure is not "the sticky write failed", and letting it propagate is honest.
        String email = consumerRepo.findByConsumerId(m.getConsumerId())
                .map(Consumer::getNormalizedEmail)
                .orElse(null);
        if (email == null) {
            log.warn("Sticky opt-out skipped: no consumer email for membership={}", m.getMembershipId());
            return;
        }
        try {
            optOutRecorder.record(email, orgId, channel, source);
        } catch (DataIntegrityViolationException violation) {
            // Expected case: a concurrent unsubscribe inserted the row first. That is the
            // outcome we wanted, and theirs is the earlier created_at — nothing to do.
            //
            // Confirm it rather than assume it. Today a duplicate PK is the only violation
            // this table can raise, but adding a column or a CHECK later would turn a real
            // write failure into a silent debug line. Re-reading settles which happened.
            if (optOutRecorder.exists(email, orgId, channel)) {
                log.debug("Sticky opt-out already recorded for membership={} channel={}",
                        m.getMembershipId(), channel);
            } else {
                log.error("Sticky opt-out write failed (not a duplicate) for membership={} org={} channel={}: {}",
                        m.getMembershipId(), orgId, channel, violation.getMessage(), violation);
            }
        } catch (RuntimeException e) {
            log.error("Sticky opt-out write failed for membership={} org={} channel={}: {}",
                    m.getMembershipId(), orgId, channel, e.getMessage(), e);
        }
    }

    private Membership requireMembership(UUID orgId, UUID membershipId) {
        return membershipRepo.findByIdAndOrgId(membershipId, orgId)
                .orElseThrow(() -> ApiException.notFound("Membership"));
    }
}

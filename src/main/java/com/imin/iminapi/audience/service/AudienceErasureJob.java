package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.model.UserRole;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Scheduled job that executes DSAR erasure after the 30-day grace period.
 * Finds memberships with status='erase_pending' and erase_at <= now(), then
 * calls DsarService.executeErase for each one.
 */
@Component
public class AudienceErasureJob {

    private static final Logger log = LoggerFactory.getLogger(AudienceErasureJob.class);

    private final MembershipRepository membershipRepo;
    private final DsarService dsarService;

    /**
     * System principal for audit-log attribution: no human actor, but the
     * <b>erasing org</b> is always known and must be carried.
     *
     * <p>This used to be a shared {@code (null, null, MEMBER, null)} constant and
     * that was a live bug, not a cosmetic one. {@code audit_logs.org_id} is NOT
     * NULL (V21:3), so the tombstone INSERT failed on the null org; the failure
     * surfaced at the {@code REQUIRES_NEW} commit, i.e. outside
     * {@code AuditLogger}'s own try/catch, and propagated into
     * {@code DsarService.executeErase} — rolling back the whole cascade. The net
     * effect was that this job erased nothing at all and logged
     * "Erasure failed" every night. Every test that covered the cascade mocked
     * {@code AuditLogger}, so nothing caught it.
     *
     * <p>Both halves of the fix matter: {@code AuditLogger} now flushes inside
     * its try so an audit failure can never again abort a business transaction,
     * and the org below makes the row legal so the tombstone actually exists.
     * A deletion you cannot prove you performed is not a deletion — and an
     * org-less audit row would in any case be invisible to every reader, since
     * they all filter on {@code org_id}.
     */
    private static AuthPrincipal systemFor(java.util.UUID orgId) {
        return new AuthPrincipal(null, orgId, UserRole.MEMBER, null);
    }

    public AudienceErasureJob(MembershipRepository membershipRepo, DsarService dsarService) {
        this.membershipRepo = membershipRepo;
        this.dsarService = dsarService;
    }

    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "audience_erasure", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void run() {
        List<Membership> due = membershipRepo.findErasureDue(Instant.now());
        log.info("AudienceErasureJob: {} memberships due for erasure", due.size());
        for (Membership m : due) {
            try {
                dsarService.executeErase(m.getOrgId(), m.getMembershipId(), systemFor(m.getOrgId()));
                log.info("Erased membership {} org {}", m.getMembershipId(), m.getOrgId());
            } catch (Exception e) {
                log.error("Erasure failed for membership {}: {}", m.getMembershipId(), e.getMessage());
            }
        }
    }
}

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

    // System principal for audit log attribution (no human actor)
    private static final AuthPrincipal SYSTEM = new AuthPrincipal(null, null, UserRole.MEMBER, null);

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
                dsarService.executeErase(m.getOrgId(), m.getMembershipId(), SYSTEM);
                log.info("Erased membership {} org {}", m.getMembershipId(), m.getOrgId());
            } catch (Exception e) {
                log.error("Erasure failed for membership {}: {}", m.getMembershipId(), e.getMessage());
            }
        }
    }
}

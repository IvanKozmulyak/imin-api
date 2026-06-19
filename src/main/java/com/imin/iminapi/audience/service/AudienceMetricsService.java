package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.dto.AudienceMetricsDto;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AudienceMetricsService {

    private final MembershipRepository membershipRepo;
    private final ConsentRecordRepository consentRepo;

    public AudienceMetricsService(MembershipRepository membershipRepo,
                                   ConsentRecordRepository consentRepo) {
        this.membershipRepo = membershipRepo;
        this.consentRepo = consentRepo;
    }

    @Transactional(readOnly = true)
    public AudienceMetricsDto compute(UUID orgId) {
        long total    = membershipRepo.countByOrgId(orgId);
        long buyers   = membershipRepo.countBuyersByOrgId(orgId);
        long prospects = membershipRepo.countProspectsByOrgId(orgId);
        long subscribed = membershipRepo.countSubscribedByOrgId(orgId);
        long explicit  = membershipRepo.countExplicitConsentByOrgId(orgId);
        long softOptIn = membershipRepo.countSoftOptInByOrgId(orgId);

        double subscribedPct = total == 0 ? 0.0 : (subscribed * 100.0 / total);

        // List growth: new memberships per week over 8 weeks
        Instant since = Instant.now().minus(56, ChronoUnit.DAYS);
        List<Membership> recent = membershipRepo.findCreatedSince(orgId, since);
        List<Integer> growth = computeWeeklyGrowth(recent, 8);

        // Repeat-attendee pct: buyers who attended > 1 event / total buyers
        long repeatAttendees = buyers == 0 ? 0 :
                recent.stream().filter(m -> m.getAttended() > 1).count();
        double repeatPct = buyers == 0 ? 0.0 : (repeatAttendees * 100.0 / buyers);

        // Unsub rate: unsub records / total subscribers
        long unsubCount = consentRepo.countUnsubsByOrgId(orgId);
        double unsubPct = subscribed == 0 ? 0.0 : (unsubCount * 100.0 / Math.max(subscribed, 1));
        double complaintPct = 0.0; // not tracked at Tier C

        return new AudienceMetricsDto(total, buyers, prospects, subscribed,
                subscribedPct, growth, repeatPct, explicit, softOptIn, unsubPct, complaintPct);
    }

    private List<Integer> computeWeeklyGrowth(List<Membership> members, int weeks) {
        Instant now = Instant.now();
        List<Integer> result = new ArrayList<>(weeks);
        for (int w = weeks - 1; w >= 0; w--) {
            Instant weekStart = now.minus((long) (w + 1) * 7, ChronoUnit.DAYS);
            Instant weekEnd   = now.minus((long) w * 7, ChronoUnit.DAYS);
            long count = members.stream()
                    .filter(m -> m.getCreatedAt() != null
                            && !m.getCreatedAt().isBefore(weekStart)
                            && m.getCreatedAt().isBefore(weekEnd))
                    .count();
            result.add((int) count);
        }
        return result;
    }
}

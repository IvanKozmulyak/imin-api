package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.audienceplan.repository.AudienceAssignmentRepository;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.audienceplan.repository.ImportRowProvenanceRepository;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Deletes one membership and the rows that hang off it, as DSAR erasure does, but writes no erasure ledger and no
 * per-member audit row: the person may come back with a new purchase or sign-up. Orders and tickets stay.
 */
@Component
public class MembershipGraphDeleter {

    /** Skip reason of a queued recipient row whose member the retention purge deleted; not a DSAR. */
    public static final String SKIP_REASON = "retention_purged";

    private final MembershipRepository membershipRepo;
    private final ConsumerRepository consumerRepo;
    private final SuppressionRepository suppressionRepo;
    private final CampaignRecipientRepository campaignRecipientRepo;
    private final FanFeatureRepository fanFeatureRepo;
    private final ImportRowProvenanceRepository provenanceRepo;
    private final AudienceAssignmentRepository assignmentRepo;
    private final BuyerAccountEmailRepository buyerAccountEmailRepo;

    public MembershipGraphDeleter(MembershipRepository membershipRepo,
                                  ConsumerRepository consumerRepo,
                                  SuppressionRepository suppressionRepo,
                                  CampaignRecipientRepository campaignRecipientRepo,
                                  FanFeatureRepository fanFeatureRepo,
                                  ImportRowProvenanceRepository provenanceRepo,
                                  AudienceAssignmentRepository assignmentRepo,
                                  BuyerAccountEmailRepository buyerAccountEmailRepo) {
        this.membershipRepo = membershipRepo;
        this.consumerRepo = consumerRepo;
        this.suppressionRepo = suppressionRepo;
        this.campaignRecipientRepo = campaignRecipientRepo;
        this.fanFeatureRepo = fanFeatureRepo;
        this.provenanceRepo = provenanceRepo;
        this.assignmentRepo = assignmentRepo;
        this.buyerAccountEmailRepo = buyerAccountEmailRepo;
    }

    /**
     * Membership lock first (the projector's lock order), then {@code stillDue} under the lock, so contact that
     * landed after the caller's scan keeps the member. False when the membership is gone or no longer due.
     */
    @Transactional
    public boolean delete(UUID orgId, UUID membershipId, BooleanSupplier stillDue) {
        Membership m = membershipRepo.lockByIdAndOrgId(membershipId, orgId).orElse(null);
        if (m == null || !stillDue.getAsBoolean()) return false;
        UUID consumerId = m.getConsumerId();
        String normalizedEmail = consumerRepo.findByConsumerId(consumerId)
                .map(Consumer::getNormalizedEmail)
                .filter(email -> !email.isBlank())
                .orElse(null);

        suppressionRepo.deleteMarketingByOrgAndMembership(orgId, membershipId);
        // Queued rows leave the queue before the redaction, so the sender never claims an address-less row.
        campaignRecipientRepo.divertPendingForDeletedMembership(membershipId, SKIP_REASON);
        campaignRecipientRepo.redactPiiByMembershipId(membershipId);
        fanFeatureRepo.deleteByMembershipId(membershipId);
        provenanceRepo.deleteByMembershipId(membershipId);
        assignmentRepo.deleteByMembershipId(membershipId);
        // Consent records and confirmation tokens cascade with the membership.
        membershipRepo.deleteByIdAndOrgId(membershipId, orgId);

        // A verified buyer-account address anchors the consumer, as in DSAR erasure.
        boolean anchored = normalizedEmail != null && buyerAccountEmailRepo.existsByVerifiedKey(normalizedEmail);
        if (consumerRepo.countMembershipsByConsumerId(consumerId) == 0 && !anchored) {
            consumerRepo.deleteByConsumerId(consumerId);
        }
        return true;
    }
}

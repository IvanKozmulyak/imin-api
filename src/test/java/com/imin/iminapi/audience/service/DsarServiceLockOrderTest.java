package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentConfirmationTokenRepository;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.audienceplan.repository.AudienceAssignmentRepository;
import com.imin.iminapi.audienceplan.repository.FanFeatureRepository;
import com.imin.iminapi.audienceplan.repository.ImportRowProvenanceRepository;
import com.imin.iminapi.audienceplan.service.ConsentGate;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The projector's lock order; reversing it deadlocks erasure against a recompute. */
class DsarServiceLockOrderTest {

    private final MembershipRepository membershipRepo = mock(MembershipRepository.class);
    private final FanFeatureRepository fanFeatureRepo = mock(FanFeatureRepository.class);
    private final DsarService service = new DsarService(membershipRepo, mock(ConsumerRepository.class),
            mock(ConsentRecordRepository.class), mock(SuppressionRepository.class), mock(ConsentService.class),
            mock(AuditLogger.class), mock(CampaignRecipientRepository.class),
            mock(NotifySubscriptionRepository.class), mock(BuyerAccountEmailRepository.class),
            mock(ErasedAddressRepository.class), mock(DsarScopeService.class), fanFeatureRepo,
            mock(ImportRowProvenanceRepository.class), mock(ConsentGate.class), mock(OrderRepository.class),
            mock(AudienceAssignmentRepository.class), mock(EventRepository.class),
            mock(ConsentConfirmationTokenRepository.class));

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"requestErase", "executeErase"})
    void locksTheMembershipBeforeDeletingItsFanFeatures(String action) {
        UUID orgId = UUID.randomUUID();
        UUID mid = UUID.randomUUID();
        Membership m = new Membership();
        m.setMembershipId(mid);
        m.setOrgId(orgId);
        m.setConsumerId(UUID.randomUUID());
        when(membershipRepo.findByIdAndOrgId(mid, orgId)).thenReturn(Optional.of(m));
        when(membershipRepo.lockByIdAndOrgId(mid, orgId)).thenReturn(Optional.of(m));
        AuthPrincipal owner = new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());

        if ("requestErase".equals(action)) service.requestErase(orgId, mid, owner);
        else service.executeErase(orgId, mid, owner);

        InOrder order = inOrder(membershipRepo, fanFeatureRepo);
        order.verify(membershipRepo).lockByIdAndOrgId(mid, orgId);
        order.verify(fanFeatureRepo).deleteByMembershipId(mid);
    }
}

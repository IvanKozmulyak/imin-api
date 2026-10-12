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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MembershipGraphDeleterTest {

    final MembershipRepository memberships = mock(MembershipRepository.class);
    final ConsumerRepository consumers = mock(ConsumerRepository.class);
    final SuppressionRepository suppressions = mock(SuppressionRepository.class);
    final CampaignRecipientRepository recipients = mock(CampaignRecipientRepository.class);
    final FanFeatureRepository features = mock(FanFeatureRepository.class);
    final ImportRowProvenanceRepository provenance = mock(ImportRowProvenanceRepository.class);
    final AudienceAssignmentRepository assignments = mock(AudienceAssignmentRepository.class);
    final BuyerAccountEmailRepository buyerEmails = mock(BuyerAccountEmailRepository.class);
    final MembershipGraphDeleter deleter = new MembershipGraphDeleter(memberships, consumers, suppressions,
            recipients, features, provenance, assignments, buyerEmails);

    final UUID orgId = UUID.randomUUID();
    final UUID membershipId = UUID.randomUUID();
    final UUID consumerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Membership m = new Membership();
        m.setMembershipId(membershipId);
        m.setOrgId(orgId);
        m.setConsumerId(consumerId);
        when(memberships.lockByIdAndOrgId(membershipId, orgId)).thenReturn(Optional.of(m));
        Consumer c = new Consumer();
        c.setConsumerId(consumerId);
        c.setNormalizedEmail("fan@example.test");
        when(consumers.findByConsumerId(consumerId)).thenReturn(Optional.of(c));
    }

    @Test
    void locksTheMembershipBeforeTouchingItsFanFeatures_thenDeletesTheGraph() {
        assertThat(deleter.delete(orgId, membershipId, () -> true)).isTrue();

        InOrder order = inOrder(memberships, features, consumers);
        order.verify(memberships).lockByIdAndOrgId(membershipId, orgId);
        order.verify(features).deleteByMembershipId(membershipId);
        order.verify(memberships).deleteByIdAndOrgId(membershipId, orgId);
        order.verify(consumers).deleteByConsumerId(consumerId);
    }

    @Test
    void noLongerDueUnderTheLock_deletesNothing() {
        assertThat(deleter.delete(orgId, membershipId, () -> false)).isFalse();

        verify(memberships).lockByIdAndOrgId(membershipId, orgId);
        verify(memberships, never()).deleteByIdAndOrgId(any(), any());
        verifyNoInteractions(suppressions, recipients, features, provenance, assignments);
        verify(consumers, never()).deleteByConsumerId(any());
    }

    @Test
    void verifiedBuyerAccountAddress_keepsTheConsumer() {
        when(buyerEmails.existsByVerifiedKey("fan@example.test")).thenReturn(true);

        assertThat(deleter.delete(orgId, membershipId, () -> true)).isTrue();

        verify(memberships).deleteByIdAndOrgId(membershipId, orgId);
        verify(consumers, never()).deleteByConsumerId(any());
    }
}

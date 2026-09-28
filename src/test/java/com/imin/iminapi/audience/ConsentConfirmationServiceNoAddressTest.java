package com.imin.iminapi.audience;

import com.imin.iminapi.audience.dto.ConsentConfirmationResponse;
import com.imin.iminapi.audience.model.ConsentConfirmationToken;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentConfirmationTokenRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.ErasedAddressRepository;
import com.imin.iminapi.audience.repository.MarketingOptOutRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.audience.service.ConsentConfirmationService;
import com.imin.iminapi.audience.service.ConsentConfirmationTokenSigner;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A membership whose consumer row has no address can never be confirmed; the H2 schema cannot hold that state. */
class ConsentConfirmationServiceNoAddressTest {

    @Test
    void memberWithoutAnAddress_isInvalid_burnsTheLink_andConfirmsNothing() {
        UUID tokenId = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-28T10:00:00Z");
        ConsentConfirmationTokenSigner signer = new ConsentConfirmationTokenSigner("unit-key-32-bytes-zzzzzzzzzzzzzzzzzz", "x", false);

        ConsentConfirmationTokenRepository tokens = mock(ConsentConfirmationTokenRepository.class);
        ConsentConfirmationToken t = new ConsentConfirmationToken();
        t.setId(tokenId);
        t.setOrgId(org);
        t.setMembershipId(member);
        t.setExpiresAt(now.plusSeconds(3600));
        when(tokens.findById(tokenId)).thenReturn(Optional.of(t));
        when(tokens.markUsed(eq(tokenId), any())).thenReturn(1);
        MembershipRepository memberships = mock(MembershipRepository.class);
        Membership m = new Membership();
        m.setMembershipId(member);
        m.setOrgId(org);
        m.setConsumerId(UUID.randomUUID());
        when(memberships.findByIdAndOrgId(member, org)).thenReturn(Optional.of(m));
        ConsumerRepository consumers = mock(ConsumerRepository.class);
        when(consumers.findByConsumerId(any())).thenReturn(Optional.empty());
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        Organization o = new Organization();
        o.setName("Vechirka");
        when(orgs.findById(org)).thenReturn(Optional.of(o));
        SuppressionRepository suppressions = mock(SuppressionRepository.class);
        when(suppressions.findMarketingByOrgAndMembership(org, member)).thenReturn(Optional.empty());
        ConsentService consentService = mock(ConsentService.class);

        ConsentConfirmationService service = new ConsentConfirmationService(tokens, signer, memberships, consumers,
                mock(ErasedAddressRepository.class), mock(MarketingOptOutRepository.class), suppressions, orgs,
                consentService, new AudiencePlanAccess(new AudiencePlanProperties()),
                mock(ApplicationEventPublisher.class), Clock.fixed(now, ZoneOffset.UTC));

        assertThat(service.preview(signer.sign(tokenId))).isEqualTo(ConsentConfirmationResponse.invalid());
        assertThat(service.confirm(signer.sign(tokenId))).isEqualTo(ConsentConfirmationResponse.invalid());
        verify(tokens).markUsed(tokenId, now);
        verify(consentService, never()).confirmPending(any(), any(), any());
    }
}

package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.service.AllCampaignsConsent;
import com.imin.iminapi.audienceplan.service.ConsentGate;
import com.imin.iminapi.marketing.dto.MarketingHubMetricsDto;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.repository.MomentumSuggestionRepository;
import com.imin.iminapi.marketing.service.CampaignAttributionService;
import com.imin.iminapi.marketing.service.MarketingHubService;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit coverage for the two config-sourced fields the controller integration test can't
 * flip in the test profile: {@code emailFrom}/{@code emailFromName} pass the marketing sender
 * config through verbatim, and {@code sendingEnabled} is true IFF a from-address is present.
 * All the number sources are stubbed to empty/0 so this test is only about the sender identity.
 */
class MarketingHubServiceTest {

    private final MembershipRepository memberships = mock(MembershipRepository.class);
    private final SendGateService sendGate = mock(SendGateService.class);
    private final MomentumSuggestionRepository suggestions = mock(MomentumSuggestionRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final CampaignRepository campaigns = mock(CampaignRepository.class);
    private final CampaignAttributionService attribution = mock(CampaignAttributionService.class);

    private final UUID orgId = UUID.randomUUID();
    private final AuthPrincipal principal = new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());

    private MarketingHubService serviceWith(MarketingEmailProperties props) {
        when(memberships.countByOrgId(eq(orgId))).thenReturn(0L);
        when(memberships.findAllMembershipIdsByOrgId(eq(orgId))).thenReturn(List.of());
        when(memberships.countSmsSubscribedByOrgId(eq(orgId))).thenReturn(0L);
        when(sendGate.evaluate(eq(orgId), any())).thenReturn(new SendGateService.GateResult(List.of(), List.of()));
        when(suggestions.findByOrgIdAndStatusOrderBySuggestedAtDesc(eq(orgId), anyString())).thenReturn(List.of());
        when(events.findMomentumCandidates(any())).thenReturn(List.of());
        when(campaigns.findByOrgCreatedSince(eq(orgId), any())).thenReturn(List.of());
        return new MarketingHubService(memberships, sendGate, suggestions, events,
                campaigns, attribution, props, new AllCampaignsConsent(planProps, consentGate));
    }

    private final AudiencePlanProperties planProps = new AudiencePlanProperties();
    private final ConsentGate consentGate = mock(ConsentGate.class);

    /** Three SendGate-sendable members, of whom ConsentGate mails only the first. */
    private MarketingHubService serviceWithThreeSendable() {
        List<UUID> members = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        MarketingHubService svc = serviceWith(new MarketingEmailProperties());
        when(memberships.findAllMembershipIdsByOrgId(eq(orgId))).thenReturn(members);
        when(sendGate.evaluate(eq(orgId), eq(members))).thenReturn(new SendGateService.GateResult(members, List.of()));
        when(consentGate.reasons(eq(orgId), eq(members))).thenReturn(java.util.Map.of(
                members.get(0), java.util.Optional.empty(),
                members.get(1), java.util.Optional.of("legacy_unproven")));
        return svc;
    }

    @Test
    void sendableEmail_consentGateAllCampaignsOff_isTheSendGateCount() {
        MarketingHubMetricsDto dto = serviceWithThreeSendable().metrics(principal);

        assertThat(dto.sendableEmail()).isEqualTo(3);
        org.mockito.Mockito.verify(consentGate, org.mockito.Mockito.never()).reasons(any(), any());
    }

    @Test
    void sendableEmail_consentGateAllCampaignsOn_countsOnlyConsentGateMailable() {
        planProps.setConsentGateAllCampaigns(true);

        MarketingHubMetricsDto dto = serviceWithThreeSendable().metrics(principal);

        // One mailable, one legacy_unproven, one ConsentGate did not return.
        assertThat(dto.sendableEmail()).isEqualTo(1);
    }

    @Test
    void sendingEnabledIsTrueWhenFromAddressConfigured() {
        MarketingEmailProperties props = new MarketingEmailProperties();
        props.setFromAddress("news@imin.wtf");
        props.setFromName("imin");

        MarketingHubMetricsDto dto = serviceWith(props).metrics(principal);

        assertThat(dto.emailFrom()).isEqualTo("news@imin.wtf");
        assertThat(dto.emailFromName()).isEqualTo("imin");
        assertThat(dto.sendingEnabled()).isTrue();
    }

    @Test
    void sendingDisabledWhenFromAddressBlank() {
        MarketingEmailProperties props = new MarketingEmailProperties(); // defaults: "" / ""

        MarketingHubMetricsDto dto = serviceWith(props).metrics(principal);

        assertThat(dto.emailFrom()).isEmpty();
        assertThat(dto.emailFromName()).isEmpty();
        assertThat(dto.sendingEnabled()).isFalse();
        // number fields degrade to 0 with empty sources — never null, never faked.
        assertThat(dto.totalContacts()).isZero();
        assertThat(dto.attributedRevMinor()).isZero();
    }
}

package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AllCampaignsConsentTest {

    private final UUID orgId = UUID.randomUUID();
    private final UUID mailable = UUID.randomUUID();
    private final UUID unproven = UUID.randomUUID();
    private final UUID absent = UUID.randomUUID();
    private final List<UUID> sendable = List.of(mailable, unproven, absent);

    private final AudiencePlanProperties props = new AudiencePlanProperties();
    private final ConsentGate gate = mock(ConsentGate.class);
    private final AllCampaignsConsent sut = new AllCampaignsConsent(props, gate);

    @Test
    void flagOff_returnsTheSendGateMembers_withoutAskingConsentGate() {
        assertThat(sut.enabled()).isFalse();
        assertThat(sut.mailable(orgId, sendable)).containsExactlyElementsOf(sendable);
        verify(gate, never()).reasons(any(), any());
    }

    @Test
    void flagOn_keepsOnlyConsentGateMailable_andDropsIdsItDidNotReturn() {
        props.setConsentGateAllCampaigns(true);
        when(gate.reasons(orgId, sendable)).thenReturn(Map.of(
                mailable, Optional.empty(),
                unproven, Optional.of(ConsentGate.LEGACY_UNPROVEN)));

        assertThat(sut.enabled()).isTrue();
        assertThat(sut.mailable(orgId, sendable)).containsExactly(mailable);
    }

    @Test
    void emptyOrNullInput_isEmpty_withoutAskingConsentGate() {
        props.setConsentGateAllCampaigns(true);

        assertThat(sut.mailable(orgId, List.of())).isEmpty();
        assertThat(sut.mailable(orgId, null)).isEmpty();
        verify(gate, never()).reasons(any(), any());
    }

    @Test
    void nullIds_areDropped_inBothFlagStates() {
        List<UUID> withNull = java.util.Arrays.asList(mailable, null, unproven);
        assertThat(sut.mailable(orgId, withNull)).containsExactly(mailable, unproven);

        props.setConsentGateAllCampaigns(true);
        when(gate.reasons(orgId, List.of(mailable, unproven))).thenReturn(Map.of(
                mailable, Optional.empty(),
                unproven, Optional.of(ConsentGate.LEGACY_UNPROVEN)));
        assertThat(sut.mailable(orgId, withNull)).containsExactly(mailable);
        verify(gate).reasons(orgId, List.of(mailable, unproven));
    }
}

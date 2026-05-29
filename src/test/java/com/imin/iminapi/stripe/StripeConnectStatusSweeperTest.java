package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StripeConnectStatusSweeperTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-29T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void sweep_resyncs_each_due_org_and_isolates_a_failing_one() {
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        StripeConnectStatusMirror mirror = mock(StripeConnectStatusMirror.class);
        when(orgs.findConnectNeedingResync(any(), any(), any()))
                .thenReturn(List.of(orgWithAccount("acct_a"), orgWithAccount("acct_b")));
        doThrow(new RuntimeException("boom")).when(mirror).syncFromStripe("acct_a");

        new StripeConnectStatusSweeper(orgs, mirror, clock).sweep();

        // The failing org is logged-and-skipped; the batch still reconciles the next org.
        verify(mirror).syncFromStripe("acct_a");
        verify(mirror).syncFromStripe("acct_b");
    }

    @Test
    void sweep_is_a_noop_when_nothing_is_due() {
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        StripeConnectStatusMirror mirror = mock(StripeConnectStatusMirror.class);
        when(orgs.findConnectNeedingResync(any(), any(), any())).thenReturn(List.of());

        new StripeConnectStatusSweeper(orgs, mirror, clock).sweep();

        verify(mirror, never()).syncFromStripe(any());
    }

    private static Organization orgWithAccount(String acct) {
        Organization o = new Organization();
        o.setId(UUID.randomUUID());
        o.setStripeAccountId(acct);
        o.setStripeConnectState(StripeConnectState.ONBOARDING);
        return o;
    }
}

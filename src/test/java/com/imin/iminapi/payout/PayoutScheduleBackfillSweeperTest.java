package com.imin.iminapi.payout;

import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.stripe.StripePayoutScheduleService;
import com.imin.iminapi.stripe.StripeProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for the Track B Phase 1 backfill sweeper — gated, batched, per-row isolated. */
class PayoutScheduleBackfillSweeperTest {

    private static StripeProperties propsWithFlag(boolean on) {
        StripeProperties p = new StripeProperties();
        p.setPayoutScheduleManual(on);
        return p;
    }

    private static Organization orgWithAccount(String acct) {
        Organization o = new Organization();
        o.setId(UUID.randomUUID());
        o.setStripeAccountId(acct);
        o.setStripePayoutsEnabled(true);
        return o;
    }

    @Test
    void sweep_flips_each_candidate_and_isolates_a_failing_one_when_flag_on() {
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        StripePayoutScheduleService svc = mock(StripePayoutScheduleService.class);
        Organization a = orgWithAccount("acct_a");
        Organization b = orgWithAccount("acct_b");
        when(orgs.findPayoutScheduleBackfillCandidates(any())).thenReturn(List.of(a, b));
        doThrow(new RuntimeException("boom")).when(svc).ensureManual(a);

        new PayoutScheduleBackfillSweeper(orgs, svc, propsWithFlag(true)).sweep();

        // The failing org is logged-and-skipped; the batch still flips the next org.
        verify(svc).ensureManual(a);
        verify(svc).ensureManual(b);
    }

    @Test
    void sweep_is_inert_when_flag_off() {
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        StripePayoutScheduleService svc = mock(StripePayoutScheduleService.class);

        new PayoutScheduleBackfillSweeper(orgs, svc, propsWithFlag(false)).sweep();

        // Flag off ⇒ no query, no flip — returns at the first line.
        verifyNoInteractions(orgs);
        verifyNoInteractions(svc);
    }

    @Test
    void sweep_is_a_noop_when_no_candidates() {
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        StripePayoutScheduleService svc = mock(StripePayoutScheduleService.class);
        when(orgs.findPayoutScheduleBackfillCandidates(any())).thenReturn(List.of());

        new PayoutScheduleBackfillSweeper(orgs, svc, propsWithFlag(true)).sweep();

        verify(svc, never()).ensureManual(any());
    }
}

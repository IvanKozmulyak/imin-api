package com.imin.iminapi.service.payout;

import com.imin.iminapi.controller.payout.dto.PayoutRowResponse;
import com.imin.iminapi.controller.payout.dto.PayoutsStatusResponse;
import com.imin.iminapi.controller.payout.dto.PayoutsSummaryResponse;
import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.settlement.Settlement;
import com.imin.iminapi.settlement.SettlementObjectType;
import com.imin.iminapi.settlement.SettlementRepository;
import com.imin.iminapi.settlement.SettlementStatus;
import com.imin.iminapi.stripe.StripeConnectService;
import com.imin.iminapi.stripe.StripeConnectState;
import com.stripe.StripeClient;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PayoutService}. Stripe is fully mocked (no network);
 * the settlement repository is mocked so the projection + summary math are
 * verified deterministically.
 */
class PayoutServiceTest {

    private final SettlementRepository settlements = mock(SettlementRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final StripeConnectService connect = mock(StripeConnectService.class);
    private final StripeClient stripeClient = mock(StripeClient.class);

    private final PayoutService sut = new PayoutService(settlements, events, connect, stripeClient);

    private static final UUID ORG = UUID.randomUUID();

    private Settlement settlement(SettlementStatus status, SettlementObjectType type,
                                  long amount, String eventIds, Instant arrival) {
        Settlement s = new Settlement();
        s.setId(UUID.randomUUID());
        s.setOrgId(ORG);
        s.setStripeObjectId("po_" + UUID.randomUUID());
        s.setObjectType(type);
        s.setAmountMinor(amount);
        s.setCurrency("EUR");
        s.setStatus(status);
        s.setEventIds(eventIds);
        s.setArrivalAt(arrival);
        s.setCreatedAt(Instant.parse("2026-06-10T00:00:00Z"));
        return s;
    }

    @Test
    void list_returnsRowsFromRepository_withSnakeCaseStatusAndEventsLabel() {
        UUID eventId = UUID.randomUUID();
        Settlement row = settlement(SettlementStatus.IN_TRANSIT, SettlementObjectType.PAYOUT,
                12_345L, eventId.toString(), Instant.parse("2026-06-18T00:00:00Z"));

        Page<Settlement> page = new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1);
        when(settlements.findByOrgIdAndObjectTypeOrderByCreatedAtDesc(
                eq(ORG), eq(SettlementObjectType.PAYOUT), any(Pageable.class))).thenReturn(page);

        Event ev = new Event();
        ev.setId(eventId);
        ev.setOrgId(ORG);
        ev.setName("Warehouse Rave");
        when(events.findAllById(any())).thenReturn(List.of(ev));

        PageResponse<PayoutRowResponse> result = sut.list(ORG, 1, 20);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.page()).isEqualTo(1);          // 1-based on the wire
        assertThat(result.pageSize()).isEqualTo(20);
        assertThat(result.items()).hasSize(1);

        PayoutRowResponse r = result.items().get(0);
        assertThat(r.id()).isEqualTo(row.getId());
        assertThat(r.orgId()).isEqualTo(ORG);
        assertThat(r.amountMinor()).isEqualTo(12_345L);
        assertThat(r.currency()).isEqualTo("EUR");
        // Critical: snake_case wire literal, not the enum name.
        assertThat(r.status()).isEqualTo("in_transit");
        assertThat(r.eventIds()).containsExactly(eventId);
        assertThat(r.stripePayoutId()).isEqualTo(row.getStripeObjectId());
        assertThat(r.eventsLabel()).isEqualTo("Warehouse Rave");
        assertThat(r.arrivesOn()).isEqualTo("2026-06-18");
    }

    @Test
    void list_clampsPageToOneBasedRequest() {
        when(settlements.findByOrgIdAndObjectTypeOrderByCreatedAtDesc(
                eq(ORG), eq(SettlementObjectType.PAYOUT), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        sut.list(ORG, 0, 20); // page 0 → clamped to internal page 0 (wire page 1)

        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(settlements)
                .findByOrgIdAndObjectTypeOrderByCreatedAtDesc(eq(ORG), eq(SettlementObjectType.PAYOUT), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(0);
    }

    @Test
    void list_requestsOnlyPayoutTypeRows_notTransfersOrAnnotations() {
        // History must return only genuine PAYOUT rows — transfer rows (and the
        // dispute/refund annotations that live on them) are never history lines.
        when(settlements.findByOrgIdAndObjectTypeOrderByCreatedAtDesc(
                eq(ORG), eq(SettlementObjectType.PAYOUT), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        sut.list(ORG, 1, 20);

        // The PAYOUT-typed finder is the only history query used; the unfiltered
        // finder (which would surface transfers/annotations) is never called.
        org.mockito.Mockito.verify(settlements)
                .findByOrgIdAndObjectTypeOrderByCreatedAtDesc(eq(ORG), eq(SettlementObjectType.PAYOUT), any(Pageable.class));
        org.mockito.Mockito.verify(settlements, org.mockito.Mockito.never())
                .findByOrgIdOrderByCreatedAtDesc(any(UUID.class), any(Pageable.class));
    }

    @Test
    void summary_sumsByStatusAndWindow() {
        when(settlements.sumAmountByOrgAndStatus(ORG, SettlementStatus.IN_TRANSIT)).thenReturn(5_000L);
        when(settlements.sumAmountByOrgAndStatus(ORG, SettlementStatus.PENDING)).thenReturn(2_500L);
        List<Object[]> countAndSum = java.util.Collections.singletonList(new Object[]{3L, 30_000L});
        when(settlements.countAndSumPaidByOrgAndTypeInWindow(eq(ORG), eq(SettlementObjectType.PAYOUT),
                eq(SettlementStatus.PAID), any(Instant.class), any(Instant.class)))
                .thenReturn(countAndSum);
        when(settlements.findByOrgIdAndStatusOrderByCreatedAtDesc(ORG, SettlementStatus.IN_TRANSIT))
                .thenReturn(List.of(settlement(SettlementStatus.IN_TRANSIT, SettlementObjectType.PAYOUT,
                        5_000L, null, Instant.parse("2026-06-20T00:00:00Z"))));

        PayoutsSummaryResponse summary = sut.summary(ORG);

        assertThat(summary.inTransit()).isEqualTo(5_000L);
        assertThat(summary.pending()).isEqualTo(2_500L);
        assertThat(summary.thisMonth()).isEqualTo(30_000L);
        assertThat(summary.thisMonthCount()).isEqualTo(3);
        assertThat(summary.arrivesOnLabel()).isEqualTo("Jun 20");
    }

    @Test
    void summary_emptyTable_yieldsZerosAndNullOptionals() {
        when(settlements.sumAmountByOrgAndStatus(ORG, SettlementStatus.IN_TRANSIT)).thenReturn(0L);
        when(settlements.sumAmountByOrgAndStatus(ORG, SettlementStatus.PENDING)).thenReturn(0L);
        List<Object[]> zeroWindow = java.util.Collections.singletonList(new Object[]{0L, 0L});
        when(settlements.countAndSumPaidByOrgAndTypeInWindow(eq(ORG), eq(SettlementObjectType.PAYOUT),
                eq(SettlementStatus.PAID), any(Instant.class), any(Instant.class)))
                .thenReturn(zeroWindow);
        when(settlements.findByOrgIdAndStatusOrderByCreatedAtDesc(ORG, SettlementStatus.IN_TRANSIT))
                .thenReturn(List.of());

        PayoutsSummaryResponse summary = sut.summary(ORG);

        assertThat(summary.inTransit()).isZero();
        assertThat(summary.pending()).isZero();
        assertThat(summary.thisMonth()).isZero();
        assertThat(summary.thisMonthCount()).isNull();   // optional → omitted when zero
        assertThat(summary.arrivesOnLabel()).isNull();   // optional → null when no in-transit arrival
    }

    @Test
    void status_notConnected_returnsFalseAndNoLiveReads() {
        AuthPrincipal p = new AuthPrincipal(UUID.randomUUID(), ORG,
                com.imin.iminapi.model.UserRole.OWNER, UUID.randomUUID());
        // No account id → not connected, and no live Stripe read is attempted.
        when(connect.getStatus(p, ORG)).thenReturn(new StripeConnectService.StatusResult(
                null, StripeConnectState.NOT_STARTED, false, false, List.of(), List.of(), null));

        PayoutsStatusResponse status = sut.status(p, ORG);

        assertThat(status.stripeConnected()).isFalse();
        assertThat(status.accountLast4()).isNull();
        assertThat(status.dashboardUrl()).isNull();
    }
}

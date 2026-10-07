package com.imin.iminapi.payout;

import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.dispute.DisputeWithholding;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.stripe.StripeProperties;
import com.stripe.StripeClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Recovery paths a database cannot force: a missing order row and the payout kill switch. */
class PostEventPayoutRecoveryUnitTest {

    @Test
    void recovery_is_inert_when_the_payout_kill_switch_is_off() {
        StripeClient stripe = mock(StripeClient.class);
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        RefundRepository refunds = mock(RefundRepository.class);
        DisputeRepository disputes = mock(DisputeRepository.class);
        RefundRecoveryMarker recoveryMarker = mock(RefundRecoveryMarker.class);
        OrderSettlementStamper stamper = mock(OrderSettlementStamper.class);
        StripeProperties props = new StripeProperties();
        props.setPayoutScheduleManual(false);
        PostEventPayoutService service = new PostEventPayoutService(stripe, props, mock(EventRepository.class), orgs,
                mock(PayoutRunRepository.class), orders, refunds, disputes,
                mock(DisputeWithholding.class), recoveryMarker, mock(DisputeRecoveryMarker.class),
                stamper, mock(ApplicationEventPublisher.class));

        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setStripeAccountId("acct_unit");
        Refund fronted = new Refund();
        fronted.setOrderId(UUID.randomUUID());
        fronted.setStripeChargeId("ch_unit");
        fronted.setAmountMinor(574);
        fronted.setPlatformFunded(true);
        when(orgs.findById(org.getId())).thenReturn(Optional.of(org));
        when(refunds.findUnrecoveredPlatformFundedByOrgId(org.getId())).thenReturn(List.of(fronted));

        service.recoverForOrg(org.getId());

        verifyNoInteractions(stripe, orgs, orders, refunds, disputes, recoveryMarker, stamper);
        assertThat(fronted.getRecoveredAt()).isNull();
    }

    @Test
    void a_platform_funded_refund_whose_order_is_missing_stays_open() {
        StripeClient stripe = mock(StripeClient.class);
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        RefundRepository refunds = mock(RefundRepository.class);
        RefundRecoveryMarker recoveryMarker = mock(RefundRecoveryMarker.class);
        StripeProperties props = new StripeProperties();
        props.setPayoutScheduleManual(true);
        PostEventPayoutService service = new PostEventPayoutService(stripe, props, mock(EventRepository.class), orgs,
                mock(PayoutRunRepository.class), orders, refunds, mock(DisputeRepository.class),
                mock(DisputeWithholding.class), recoveryMarker, mock(DisputeRecoveryMarker.class),
                mock(OrderSettlementStamper.class), mock(ApplicationEventPublisher.class));

        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setStripeAccountId("acct_unit");
        Refund fronted = new Refund();
        fronted.setOrderId(UUID.randomUUID());
        fronted.setStripeChargeId("ch_unit");
        fronted.setAmountMinor(574);
        fronted.setApplicationFeeRefundMinor(74);
        fronted.setPlatformFunded(true);
        when(orgs.findById(org.getId())).thenReturn(Optional.of(org));
        when(refunds.findUnrecoveredPlatformFundedByOrgId(org.getId())).thenReturn(List.of(fronted));
        when(orders.findById(fronted.getOrderId())).thenReturn(Optional.empty());

        assertThatCode(() -> service.recoverForOrg(org.getId())).doesNotThrowAnyException();

        verifyNoInteractions(stripe);
        verifyNoInteractions(recoveryMarker);
        assertThat(fronted.getRecoveredAt()).isNull();
    }
}

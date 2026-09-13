package com.imin.iminapi.stripe;

import com.imin.iminapi.refund.RefundReason;
import com.stripe.StripeClient;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.stripe.service.ApplicationFeeRefundService;
import com.stripe.service.ApplicationFeeService;
import com.stripe.service.ChargeService;
import com.stripe.service.RefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StripeRefundServiceTest {

    private RefundService refundSvc;
    private ChargeService chargeSvc;
    private ApplicationFeeService appFeeSvc;
    private ApplicationFeeRefundService feeRefundSvc;
    private StripeRefundService service;

    @BeforeEach
    void setUp() {
        StripeClient stripeClient = mock(StripeClient.class);
        refundSvc = mock(RefundService.class);
        chargeSvc = mock(ChargeService.class);
        appFeeSvc = mock(ApplicationFeeService.class);
        feeRefundSvc = mock(ApplicationFeeRefundService.class);
        when(stripeClient.refunds()).thenReturn(refundSvc);
        // Stubbed but never expected — the point of refundIssuesExactlyOneStripeCall.
        lenient().when(stripeClient.charges()).thenReturn(chargeSvc);
        lenient().when(stripeClient.applicationFees()).thenReturn(appFeeSvc);
        lenient().when(appFeeSvc.refunds()).thenReturn(feeRefundSvc);
        service = new StripeRefundService(stripeClient);
    }

    private Refund stubRefund(String id) throws Exception {
        Refund stub = new Refund();
        stub.setId(id);
        stub.setCharge("ch_" + id);
        stub.setStatus("pending");
        when(refundSvc.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(stub);
        return stub;
    }

    @Test
    void create_passesAmountReasonAndIdempotencyKey() throws Exception {
        stubRefund("re_test_123");

        Refund out = service.create(
            "pi_test_1", 5000L, "eur",
            RefundReason.REQUESTED_BY_CUSTOMER, 0L, true, "refund_xyz");

        assertThat(out.getId()).isEqualTo("re_test_123");

        ArgumentCaptor<RefundCreateParams> paramsCap = ArgumentCaptor.forClass(RefundCreateParams.class);
        ArgumentCaptor<RequestOptions> optsCap = ArgumentCaptor.forClass(RequestOptions.class);
        verify(refundSvc).create(paramsCap.capture(), optsCap.capture());

        RefundCreateParams p = paramsCap.getValue();
        assertThat(p.getPaymentIntent()).isEqualTo("pi_test_1");
        assertThat(p.getAmount()).isEqualTo(5000L);
        assertThat(p.getReason()).isEqualTo(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER);
        assertThat(optsCap.getValue().getIdempotencyKey()).isEqualTo("refund_xyz");
    }

    @Test
    void create_otherReason_omitsStripeReason() throws Exception {
        stubRefund("re_other");

        service.create("pi_1", 1000L, "eur", RefundReason.OTHER, 0L, true, "k");

        ArgumentCaptor<RefundCreateParams> cap = ArgumentCaptor.forClass(RefundCreateParams.class);
        verify(refundSvc).create(cap.capture(), any(RequestOptions.class));
        assertThat(cap.getValue().getReason()).isNull();
    }

    @Test
    void refundIssuesExactlyOneStripeCall() throws Exception {
        stubRefund("re_test_2");

        service.create("pi_test_2", 2500L, "eur",
                       RefundReason.REQUESTED_BY_CUSTOMER, 149L, true, "refund_pqr");

        verifyNoInteractions(feeRefundSvc);
        verifyNoInteractions(appFeeSvc);
        verifyNoInteractions(chargeSvc);
        verify(refundSvc).create(any(RefundCreateParams.class), any(RequestOptions.class));
    }

    @Test
    void reverseTransferRefundAlsoRefundsTheApplicationFee() throws Exception {
        stubRefund("re_fee_share");

        service.create("pi_fee", 1149L, "eur", RefundReason.REQUESTED_BY_CUSTOMER, 149L, true, "k_fee");

        ArgumentCaptor<RefundCreateParams> cap = ArgumentCaptor.forClass(RefundCreateParams.class);
        verify(refundSvc).create(cap.capture(), any(RequestOptions.class));
        assertThat(cap.getValue().getReverseTransfer()).isEqualTo(Boolean.TRUE);
        assertThat(cap.getValue().getRefundApplicationFee())
            .as("the reversal pulls the GROSS off the connected balance, so Stripe must return "
                + "the organizer's proportional fee share or the organizer eats the platform fee")
            .isEqualTo(Boolean.TRUE);
    }

    @Test
    void reverseTransferRefundWithNoFeeDoesNotAskStripeToRefundOne() throws Exception {
        stubRefund("re_no_fee");

        service.create("pi_no_fee", 1000L, "eur", RefundReason.REQUESTED_BY_CUSTOMER, 0L, true, "k_no_fee");

        ArgumentCaptor<RefundCreateParams> cap = ArgumentCaptor.forClass(RefundCreateParams.class);
        verify(refundSvc).create(cap.capture(), any(RequestOptions.class));
        assertThat(cap.getValue().getReverseTransfer()).isEqualTo(Boolean.TRUE);
        assertThat(cap.getValue().getRefundApplicationFee())
            .as("a charge with application_fee_amount = 0 has no fee to refund and Stripe "
                + "rejects the flag")
            .isEqualTo(Boolean.FALSE);
    }

    @Test
    void platformFundedRefundKeepsTheFee() throws Exception {
        stubRefund("re_test_4");

        service.create("pi_y", 1000L, "eur", RefundReason.OTHER, 50L, false, "k3:platform");

        ArgumentCaptor<RefundCreateParams> cap = ArgumentCaptor.forClass(RefundCreateParams.class);
        verify(refundSvc).create(cap.capture(), any(RequestOptions.class));
        assertThat(cap.getValue().getReverseTransfer())
            .as("a platform-funded refund must NOT try to pull from the short connected balance")
            .isEqualTo(Boolean.FALSE);
        assertThat(cap.getValue().getRefundApplicationFee())
            .as("the payout sweep reverses amount − feeShare off the destination transfer, so a fee "
                + "refund here would hand the connected account a share nothing claws back")
            .isEqualTo(Boolean.FALSE);
    }
}

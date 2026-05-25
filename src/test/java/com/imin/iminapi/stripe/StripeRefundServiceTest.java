package com.imin.iminapi.stripe;

import com.imin.iminapi.refund.RefundReason;
import com.stripe.StripeClient;
import com.stripe.model.Charge;
import com.stripe.model.FeeRefund;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.ApplicationFeeRefundCreateParams;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StripeRefundServiceTest {

    private StripeClient stripeClient;
    private RefundService refundSvc;
    private ChargeService chargeSvc;
    private ApplicationFeeService appFeeSvc;
    private ApplicationFeeRefundService feeRefundSvc;
    private StripeRefundService service;

    @BeforeEach
    void setUp() {
        stripeClient = mock(StripeClient.class);
        refundSvc = mock(RefundService.class);
        chargeSvc = mock(ChargeService.class);
        appFeeSvc = mock(ApplicationFeeService.class);
        feeRefundSvc = mock(ApplicationFeeRefundService.class);
        when(stripeClient.refunds()).thenReturn(refundSvc);
        when(stripeClient.charges()).thenReturn(chargeSvc);
        when(stripeClient.applicationFees()).thenReturn(appFeeSvc);
        when(appFeeSvc.refunds()).thenReturn(feeRefundSvc);
        service = new StripeRefundService(stripeClient);
    }

    @Test
    void create_passesAmountReasonReverseTransferAndIdempotencyKey_skipsFeeWhenZero() throws Exception {
        Refund stub = new Refund();
        stub.setId("re_test_123");
        stub.setCharge("ch_test_abc");
        stub.setStatus("pending");
        when(refundSvc.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(stub);

        Refund out = service.create(
            "pi_test_1", 5000L, "eur",
            RefundReason.REQUESTED_BY_CUSTOMER, 0L, "refund_xyz");

        assertThat(out.getId()).isEqualTo("re_test_123");

        ArgumentCaptor<RefundCreateParams> paramsCap = ArgumentCaptor.forClass(RefundCreateParams.class);
        ArgumentCaptor<RequestOptions> optsCap = ArgumentCaptor.forClass(RequestOptions.class);
        verify(refundSvc).create(paramsCap.capture(), optsCap.capture());

        RefundCreateParams p = paramsCap.getValue();
        assertThat(p.getPaymentIntent()).isEqualTo("pi_test_1");
        assertThat(p.getAmount()).isEqualTo(5000L);
        assertThat(p.getReverseTransfer()).isEqualTo(Boolean.TRUE);
        assertThat(p.getRefundApplicationFee()).isEqualTo(Boolean.FALSE);
        assertThat(p.getReason()).isEqualTo(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER);
        assertThat(optsCap.getValue().getIdempotencyKey()).isEqualTo("refund_xyz");

        // appFeeRefundMinor == 0 → no separate fee refund call
        verifyNoInteractions(chargeSvc);
        verify(feeRefundSvc, never()).create(any(), any(ApplicationFeeRefundCreateParams.class), any());
    }

    @Test
    void create_otherReason_omitsStripeReason() throws Exception {
        Refund stub = new Refund();
        stub.setId("re_other");
        stub.setCharge("ch_x");
        stub.setStatus("pending");
        when(refundSvc.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(stub);

        service.create("pi_1", 1000L, "eur", RefundReason.OTHER, 0L, "k");

        ArgumentCaptor<RefundCreateParams> cap = ArgumentCaptor.forClass(RefundCreateParams.class);
        verify(refundSvc).create(cap.capture(), any(RequestOptions.class));
        assertThat(cap.getValue().getReason()).isNull();
    }

    @Test
    void create_withAppFeeRefund_alsoCallsApplicationFeeRefund() throws Exception {
        Refund stubRefund = new Refund();
        stubRefund.setId("re_test_2");
        stubRefund.setCharge("ch_test_xyz");
        stubRefund.setStatus("pending");
        when(refundSvc.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(stubRefund);

        Charge stubCharge = new Charge();
        stubCharge.setApplicationFee("fee_test_1");
        when(chargeSvc.retrieve(eq("ch_test_xyz"))).thenReturn(stubCharge);

        when(feeRefundSvc.create(eq("fee_test_1"), any(ApplicationFeeRefundCreateParams.class), any(RequestOptions.class)))
            .thenReturn(new FeeRefund());

        service.create("pi_test_2", 2500L, "eur",
                       RefundReason.REQUESTED_BY_CUSTOMER, 200L, "refund_pqr");

        ArgumentCaptor<ApplicationFeeRefundCreateParams> feeCap =
            ArgumentCaptor.forClass(ApplicationFeeRefundCreateParams.class);
        ArgumentCaptor<RequestOptions> feeOptsCap = ArgumentCaptor.forClass(RequestOptions.class);
        verify(feeRefundSvc).create(eq("fee_test_1"), feeCap.capture(), feeOptsCap.capture());
        assertThat(feeCap.getValue().getAmount()).isEqualTo(200L);
        assertThat(feeOptsCap.getValue().getIdempotencyKey()).isEqualTo("refund_pqr_fee");
    }

    @Test
    void create_chargeWithoutApplicationFee_skipsFeeRefund() throws Exception {
        Refund stubRefund = new Refund();
        stubRefund.setId("re_test_3");
        stubRefund.setCharge("ch_test_no_fee");
        stubRefund.setStatus("pending");
        when(refundSvc.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(stubRefund);

        Charge stubCharge = new Charge();
        stubCharge.setApplicationFee(null);
        when(chargeSvc.retrieve(eq("ch_test_no_fee"))).thenReturn(stubCharge);

        service.create("pi_x", 1000L, "eur", RefundReason.OTHER, 50L, "k2");

        verify(feeRefundSvc, never()).create(any(), any(ApplicationFeeRefundCreateParams.class), any());
    }
}

package com.imin.iminapi.refund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class RefundControllerTest {

    @Autowired MockMvc mvc;
    final ObjectMapper om = new ObjectMapper();

    @MockitoBean RefundService refundService;
    @MockitoBean RefundTicketRepository refundTicketRepository;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final UUID TICKET = UUID.fromString("00000000-0000-0000-0000-000000000004");
    static final UUID REFUND = UUID.fromString("00000000-0000-0000-0000-000000000005");

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = StubFactory.class)
    public @interface WithStubOrganizer {}

    public static class StubFactory implements WithSecurityContextFactory<WithStubOrganizer> {
        @Override public org.springframework.security.core.context.SecurityContext createSecurityContext(WithStubOrganizer ann) {
            AuthPrincipal p = new AuthPrincipal(USER, ORG, UserRole.OWNER, UUID.randomUUID());
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    @Test
    @WithStubOrganizer
    void missing_idempotency_key_returns_400() throws Exception {
        when(refundService.createRefund(eq(ORDER), any(), eq(null), any(), any()))
            .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_IDEMPOTENCY_KEY,
                "Idempotency-Key header is required"));

        mvc.perform(post("/api/v1/orders/{id}/refund", ORDER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                    "ticketIds", List.of(TICKET.toString()),
                    "reason", "other"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    @WithStubOrganizer
    void cross_org_returns_404() throws Exception {
        when(refundService.createRefund(eq(ORDER), any(), eq("k"), any(), any()))
            .thenThrow(ApiException.notFound("Order"));

        mvc.perform(post("/api/v1/orders/{id}/refund", ORDER)
                .header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                    "ticketIds", List.of(TICKET.toString()),
                    "reason", "other"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @WithStubOrganizer
    void happy_path_returns_202_with_refund_response() throws Exception {
        Refund r = new Refund();
        r.setId(REFUND);
        r.setOrderId(ORDER);
        r.setStripeRefundId("re_1");
        r.setAmountMinor(5000);
        r.setCurrency("eur");
        r.setApplicationFeeRefundMinor(300);
        r.setStatus(RefundStatus.PENDING);
        r.setReason(RefundReason.REQUESTED_BY_CUSTOMER);
        when(refundService.createRefund(eq(ORDER), any(), eq("k"), any(), any())).thenReturn(r);
        when(refundTicketRepository.findTicketIdsByRefundId(REFUND)).thenReturn(List.of(TICKET));

        mvc.perform(post("/api/v1/orders/{id}/refund", ORDER)
                .header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                    "ticketIds", List.of(TICKET.toString()),
                    "reason", "requested_by_customer"))))   // lowercase wire format
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.id").value(REFUND.toString()))
            .andExpect(jsonPath("$.status").value("pending"))
            .andExpect(jsonPath("$.amountMinor").value(5000))
            .andExpect(jsonPath("$.applicationFeeRefundMinor").value(300))
            .andExpect(jsonPath("$.reason").value("requested_by_customer"))
            .andExpect(jsonPath("$.ticketIds[0]").value(TICKET.toString()));
    }

    @Test
    @WithStubOrganizer
    void accepts_lowercase_reason_from_frontend() throws Exception {
        // Contract: FE sends RefundReason as the lowercase wire format (matching
        // the response). Backend must accept it via @JsonCreator on the enum.
        Refund r = new Refund();
        r.setId(REFUND);
        r.setOrderId(ORDER);
        r.setStatus(RefundStatus.PENDING);
        r.setReason(RefundReason.DUPLICATE);
        r.setCurrency("eur");
        when(refundService.createRefund(eq(ORDER), any(), eq("k"),
            any(), eq(RefundReason.DUPLICATE))).thenReturn(r);
        when(refundTicketRepository.findTicketIdsByRefundId(REFUND)).thenReturn(List.of(TICKET));

        mvc.perform(post("/api/v1/orders/{id}/refund", ORDER)
                .header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                    "ticketIds", List.of(TICKET.toString()),
                    "reason", "duplicate"))))   // ← lowercase, as the FE sends
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.reason").value("duplicate"));
    }

    @Test
    @WithStubOrganizer
    void empty_ticket_ids_returns_400_from_bean_validation() throws Exception {
        mvc.perform(post("/api/v1/orders/{id}/refund", ORDER)
                .header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                    "ticketIds", List.of(),
                    "reason", "other"))))
            .andExpect(status().isBadRequest());
    }
}

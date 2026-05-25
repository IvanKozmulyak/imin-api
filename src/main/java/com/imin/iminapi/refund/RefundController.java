package com.imin.iminapi.refund;

import com.imin.iminapi.refund.dto.CreateRefundRequest;
import com.imin.iminapi.refund.dto.RefundResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Organizer-authenticated refund surface.
 *
 * <p>The {@code POST /refund} returns 202 Accepted because the refund is async:
 * Stripe takes the request and confirms it later via {@code charge.refund.updated}.
 * Idempotent replays (same {@code Idempotency-Key}) return 200 with the existing
 * row; this controller doesn't distinguish, but clients should treat both as success.
 */
@RestController
@RequestMapping("/api/v1/orders/{orderId}")
public class RefundController {

    private final RefundService refundService;
    private final RefundTicketRepository refundTicketRepository;

    public RefundController(RefundService refundService,
                            RefundTicketRepository refundTicketRepository) {
        this.refundService = refundService;
        this.refundTicketRepository = refundTicketRepository;
    }

    @PostMapping("/refund")
    public ResponseEntity<RefundResponse> refund(
        @PathVariable UUID orderId,
        @CurrentUser AuthPrincipal principal,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody CreateRefundRequest body
    ) {
        Refund refund = refundService.createRefund(orderId, principal, idempotencyKey,
            body.ticketIds(), body.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(refund));
    }

    @GetMapping("/refunds")
    public List<RefundResponse> list(@PathVariable UUID orderId,
                                     @CurrentUser AuthPrincipal principal) {
        return refundService.listForOrder(orderId, principal).stream()
            .map(this::toResponse)
            .toList();
    }

    private RefundResponse toResponse(Refund r) {
        List<UUID> ticketIds = refundTicketRepository.findTicketIdsByRefundId(r.getId());
        return RefundResponse.from(r, ticketIds);
    }
}

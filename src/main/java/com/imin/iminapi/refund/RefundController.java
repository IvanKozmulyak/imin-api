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
 * Stripe takes the request and confirms it later via the refund webhooks. Responses:
 * <ul>
 *   <li>202 with {@code status} {@code pending}/{@code succeeded}: Stripe accepted the refund.</li>
 *   <li>202 with {@code status:"failed"}/{@code "canceled"}: Stripe created the refund object and it
 *       failed synchronously ({@code failureMessage}); its tickets are refundable again.</li>
 *   <li>202 with {@code status:"requested"}: a same-key replay of an attempt whose Stripe outcome is
 *       still unknown. Same-key replays always return the existing row.</li>
 *   <li>409 {@code REFUND_IN_PROGRESS} (fields {@code refundId}, optional {@code stripeCode}): imin
 *       could not learn Stripe's outcome; the tickets stay claimed and the reconciler resolves it.
 *       Never re-issue with a new key.</li>
 *   <li>409 {@code TICKET_ALREADY_REFUNDED}: a ticket is claimed by another refund, including one in
 *       progress. 422 {@code STRIPE_REFUND_FAILED} (field {@code stripeCode}): Stripe refused.</li>
 * </ul>
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

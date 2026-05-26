package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.refund.RefundRequestService;
import com.imin.iminapi.refund.dto.PublicRefundFormResponse;
import com.imin.iminapi.refund.dto.PublicRefundSubmitRequest;
import com.imin.iminapi.refund.dto.PublicRefundSubmitResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated buyer-facing refund-request surface.
 *
 * <p>{@code POST /} always returns 200 regardless of whether the email matches
 * an order — the anti-enumeration response is identical so a probe can't tell
 * which addresses purchased on imin. The actual link-issuance work is delegated
 * to {@link RefundRequestService#requestLink(String, String)}.
 *
 * <p>{@code GET /by-token/{t}} loads the form context (order + tickets + event)
 * for a valid, unexpired, unconsumed token; {@code POST /by-token/{t}} submits
 * the request and atomically burns the token.
 */
@RestController
@RequestMapping("/api/v1/public/refund-requests")
public class PublicRefundRequestController {

    private final RefundRequestService service;

    public PublicRefundRequestController(RefundRequestService service) {
        this.service = service;
    }

    public record LinkRequest(String email) {}
    public record LinkResponse(boolean ok) {}

    @PostMapping
    public ResponseEntity<LinkResponse> requestLink(@RequestBody(required = false) LinkRequest req,
                                                    HttpServletRequest http) {
        if (req != null) {
            service.requestLink(req.email(), http.getRemoteAddr());
        }
        return ResponseEntity.ok(new LinkResponse(true));
    }

    @GetMapping("/by-token/{token}")
    public PublicRefundFormResponse formContext(@PathVariable("token") String token) {
        return service.lookupByToken(token);
    }

    @PostMapping("/by-token/{token}")
    public ResponseEntity<PublicRefundSubmitResponse> submit(@PathVariable("token") String token,
                                                             @Valid @RequestBody PublicRefundSubmitRequest body) {
        PublicRefundSubmitResponse resp = service.submitByToken(token, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }
}

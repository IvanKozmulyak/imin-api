package com.imin.iminapi.refund;

import com.imin.iminapi.refund.dto.RefundRequestApproveRequest;
import com.imin.iminapi.refund.dto.RefundRequestDecisionResponse;
import com.imin.iminapi.refund.dto.RefundRequestDetailResponse;
import com.imin.iminapi.refund.dto.RefundRequestRejectRequest;
import com.imin.iminapi.refund.dto.RefundRequestSummaryResponse;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Organizer-authenticated refund-request surface.
 *
 * <p>All endpoints scope by {@code orgId} from the path and gate against
 * {@code principal.orgId()} — a mismatch returns 404 (not 403) so the
 * existence of the request stays leak-safe.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/refund-requests")
public class RefundRequestController {

    private final RefundRequestService service;

    public RefundRequestController(RefundRequestService service) {
        this.service = service;
    }

    @GetMapping
    public List<RefundRequestSummaryResponse> list(@PathVariable UUID orgId,
                                                   @CurrentUser AuthPrincipal principal,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) UUID eventId,
                                                   @RequestParam(required = false) String search,
                                                   @RequestParam(defaultValue = "25") int limit) {
        if (!orgId.equals(principal.orgId())) throw ApiException.notFound("Org");
        List<RefundRequestStatus> statuses = parseStatuses(status);
        // `search` = the refund reference a customer quoted (REQ-8K2M-26 / 8K2M-26)
        // or part of their email. Blank/absent behaves exactly as before.
        return service.listRequests(orgId, eventId, statuses, search, limit);
    }

    @GetMapping("/{id}")
    public RefundRequestDetailResponse get(@PathVariable UUID orgId,
                                           @PathVariable UUID id,
                                           @CurrentUser AuthPrincipal principal) {
        if (!orgId.equals(principal.orgId())) throw ApiException.notFound("Org");
        return service.getRequest(id, orgId);
    }

    @PostMapping("/{id}/approve")
    public RefundRequestDecisionResponse approve(@PathVariable UUID orgId,
                                                 @PathVariable UUID id,
                                                 @CurrentUser AuthPrincipal principal,
                                                 @RequestBody(required = false) RefundRequestApproveRequest body) {
        if (!orgId.equals(principal.orgId())) throw ApiException.notFound("Org");
        RefundRequestApproveRequest effective = (body == null)
            ? new RefundRequestApproveRequest(false, "")
            : body;
        return service.approveRequest(id, principal, effective);
    }

    @PostMapping("/{id}/reject")
    public RefundRequestDecisionResponse reject(@PathVariable UUID orgId,
                                                @PathVariable UUID id,
                                                @CurrentUser AuthPrincipal principal,
                                                @Valid @RequestBody RefundRequestRejectRequest body) {
        if (!orgId.equals(principal.orgId())) throw ApiException.notFound("Org");
        return service.rejectRequest(id, principal, body);
    }

    private List<RefundRequestStatus> parseStatuses(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> RefundRequestStatus.valueOf(s.toUpperCase(Locale.ROOT)))
            .toList();
    }
}

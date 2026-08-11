package com.imin.iminapi.refund;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.OrderRecoveryAttempt;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.refund.dto.ProposedRefundResponse;
import com.imin.iminapi.refund.dto.PublicRefundFormResponse;
import com.imin.iminapi.refund.dto.PublicRefundSubmitRequest;
import com.imin.iminapi.refund.dto.PublicRefundSubmitResponse;
import com.imin.iminapi.refund.dto.RefundRequestApproveRequest;
import com.imin.iminapi.refund.dto.RefundRequestDecisionResponse;
import com.imin.iminapi.refund.dto.RefundRequestDetailResponse;
import com.imin.iminapi.refund.dto.RefundRequestRejectRequest;
import com.imin.iminapi.refund.dto.RefundRequestSummaryResponse;
import com.imin.iminapi.refund.event.RefundRequestRejectedEvent;
import com.imin.iminapi.refund.event.RefundRequestSubmittedEvent;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRecoveryAttemptRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.ticket.TicketProperties;
import com.imin.iminapi.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;

/**
 * Orchestration for buyer-initiated refund requests.
 *
 * <p>Public-surface methods (link, lookup, submit) are anti-enumeration-safe:
 * they never reveal whether an email matches an order. Organizer-surface
 * methods (approve, reject, list) require an authenticated principal whose
 * orgId is verified against the request's orgId.
 */
@Service
public class RefundRequestService {

    private static final Logger log = LoggerFactory.getLogger(RefundRequestService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final OrderRepository orders;
    private final EventRepository events;
    private final OrderRecoveryAttemptRepository attempts;
    private final RefundRequestTokenRepository tokens;
    private final RefundRequestRepository requests;
    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties emailProps;
    private final TicketProperties ticketProps;
    private final ApplicationEventPublisher publisher;
    private final TicketRepository tickets;
    private final RefundTicketRepository refundTickets;
    private final TicketTierRepository tiers;
    private final RefundService refundService;
    private final RefundReferenceGenerator references;

    public RefundRequestService(OrderRepository orders,
                                EventRepository events,
                                OrderRecoveryAttemptRepository attempts,
                                RefundRequestTokenRepository tokens,
                                RefundRequestRepository requests,
                                EmailService email,
                                EmailTemplateRenderer renderer,
                                EmailProperties emailProps,
                                TicketProperties ticketProps,
                                ApplicationEventPublisher publisher,
                                TicketRepository tickets,
                                RefundTicketRepository refundTickets,
                                TicketTierRepository tiers,
                                RefundService refundService,
                                RefundReferenceGenerator references) {
        this.orders = orders;
        this.events = events;
        this.attempts = attempts;
        this.tokens = tokens;
        this.requests = requests;
        this.email = email;
        this.renderer = renderer;
        this.emailProps = emailProps;
        this.ticketProps = ticketProps;
        this.publisher = publisher;
        this.tickets = tickets;
        this.refundTickets = refundTickets;
        this.tiers = tiers;
        this.refundService = refundService;
        this.references = references;
    }

    @Transactional
    public void requestLink(String rawEmail, String clientIp) {
        if (rawEmail == null) return;
        String normalized = rawEmail.trim().toLowerCase(Locale.ROOT);

        // Log attempt up-front so the rate-limit counters tick on invalid input too.
        OrderRecoveryAttempt a = new OrderRecoveryAttempt();
        a.setEmail(normalized);
        a.setIpHash(hashIp(clientIp));
        attempts.save(a);

        if (normalized.isEmpty() || !normalized.contains("@")) return;

        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        long byEmail = attempts.countByEmailAndAttemptedAtAfter(normalized, cutoff);
        long byIp = attempts.countByIpHashAndAttemptedAtAfter(hashIp(clientIp), cutoff);
        int cap = ticketProps.getRecoveryMaxPerHour();
        if (byEmail > cap || byIp > cap) {
            log.info("[refund-request] rate-limited email={} byEmail={} byIp={}",
                normalized, byEmail, byIp);
            return;
        }

        Instant recoveryCutoff = Instant.now()
            .minus(Duration.ofDays(ticketProps.getRecoveryWindowDays()));
        List<Order> found = orders.findRecentForRecovery(normalized, null, recoveryCutoff);
        Order chosen = found.stream()
            .filter(o -> o.getTotalMinor() > 0 && o.getStripePaymentIntentId() != null
                && !o.getStripePaymentIntentId().isBlank())
            .findFirst()
            .orElse(null);
        if (chosen == null) {
            log.info("[refund-request] no refundable order for {}", normalized);
            return;
        }

        String raw = generateRawToken();
        String hash = sha256Hex(raw);

        RefundRequestToken token = new RefundRequestToken();
        token.setTokenHash(hash);
        token.setOrderId(chosen.getId());
        token.setEmailNormalized(normalized);
        token.setExpiresAt(Times.nowMicros()
            .plus(Duration.ofMinutes(emailProps.getRefundRequestTokenTtlMinutes())));
        tokens.save(token);

        String url = baseUrl() + "/refund/" + raw;
        Map<String, String> values = new LinkedHashMap<>();
        values.put("link", url);
        values.put("ttlMinutes", String.valueOf(emailProps.getRefundRequestTokenTtlMinutes()));
        EmailTemplateRenderer.Rendered r = renderer.render("refund-request-link", values);

        try {
            email.send(normalized, "Request a refund · imin", r.html(), r.text());
            log.info("[refund-request] token-issued orderId={} emailHash={}",
                chosen.getId(), sha256Hex(normalized));
        } catch (Exception e) {
            log.warn("[refund-request] link email failed for {}: {}", normalized, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public PublicRefundFormResponse lookupByToken(String rawToken) {
        RefundRequestToken token = tokens.findByTokenHash(sha256Hex(rawToken))
            .filter(t -> t.getConsumedAt() == null)
            .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
            .orElseThrow(() -> new ApiException(
                HttpStatus.GONE,
                ErrorCode.REFUND_TOKEN_EXPIRED_OR_CONSUMED,
                "Refund link is no longer valid"));

        Order order = orders.findById(token.getOrderId())
            .orElseThrow(() -> new ApiException(
                HttpStatus.GONE,
                ErrorCode.REFUND_TOKEN_EXPIRED_OR_CONSUMED,
                "Refund link is no longer valid"));

        Event event = events.findById(order.getEventId()).orElse(null);

        List<Ticket> refundable = refundableTicketsFor(order);
        if (refundable.isEmpty()) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.NO_REFUNDABLE_TICKETS,
                "All tickets on this order have already been refunded or used");
        }

        long estimated = refundService.computeRefundAmountMinor(order, refundable);

        Map<UUID, String> tierNames = new HashMap<>();
        for (Ticket t : refundable) {
            tierNames.computeIfAbsent(t.getTierId(), id -> tiers.findById(id)
                .map(TicketTier::getName).orElse(""));
        }

        return new PublicRefundFormResponse(
            order.getId(),
            new PublicRefundFormResponse.EventSummary(
                event == null ? null : event.getName(),
                event == null ? null : event.getStartsAt(),
                event == null ? null : event.getTimezone(),
                event == null ? null : event.getVenueName(),
                order.getCurrency()),
            refundable.stream()
                .map(t -> new PublicRefundFormResponse.TicketLine(
                    t.getId(),
                    tierNames.getOrDefault(t.getTierId(), ""),
                    t.getPriceMinor()))
                .toList(),
            estimated,
            order.getCurrency(),
            PublicRefundFormResponse.defaultReasons(),
            // Null unless this order ALREADY has an open request — see the DTO javadoc.
            requests.findFirstByOrderIdAndStatus(order.getId(), RefundRequestStatus.PENDING)
                .map(RefundRequest::getReference)
                .orElse(null)
        );
    }

    private List<Ticket> refundableTicketsFor(Order order) {
        List<Ticket> all = tickets.findByOrderId(order.getId());
        List<UUID> ids = all.stream().map(Ticket::getId).toList();
        Set<UUID> alreadyRefunded = ids.isEmpty()
            ? Set.of() : refundTickets.findRefundedTicketIds(ids);
        return all.stream()
            .filter(t -> !alreadyRefunded.contains(t.getId()))
            .filter(t -> !Ticket.STATE_REDEEMED.equals(t.getState()))
            .toList();
    }

    @Transactional
    public PublicRefundSubmitResponse submitByToken(String rawToken, PublicRefundSubmitRequest body) {
        RefundRequestToken token = tokens.findByTokenHash(sha256Hex(rawToken))
            .filter(t -> t.getConsumedAt() == null)
            .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
            .orElseThrow(() -> new ApiException(
                HttpStatus.GONE,
                ErrorCode.REFUND_TOKEN_EXPIRED_OR_CONSUMED,
                "Refund link is no longer valid"));

        Order order = orders.findById(token.getOrderId())
            .orElseThrow(() -> new ApiException(
                HttpStatus.GONE,
                ErrorCode.REFUND_TOKEN_EXPIRED_OR_CONSUMED,
                "Refund link is no longer valid"));

        List<Ticket> refundable = refundableTicketsFor(order);
        if (refundable.isEmpty()) {
            // Burn the token so a retry doesn't hit lookup-then-409 again.
            token.setConsumedAt(Times.nowMicros());
            tokens.save(token);
            throw new ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.NO_REFUNDABLE_TICKETS,
                "All tickets on this order have already been refunded or used");
        }

        if (requests.existsByOrderIdAndStatus(order.getId(), RefundRequestStatus.PENDING)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.REFUND_REQUEST_ALREADY_OPEN,
                "A refund request is already open for this order");
        }

        RefundRequest rr = new RefundRequest();
        rr.setOrderId(order.getId());
        rr.setOrgId(order.getOrgId());
        rr.setEventId(order.getEventId());
        rr.setBuyerEmail(order.getEmail() == null ? "" : order.getEmail().toLowerCase(Locale.ROOT));
        rr.setBuyerPhone(body.phone());
        rr.setReason(body.reason());
        rr.setExplanation(body.explanation());
        rr.setStatus(RefundRequestStatus.PENDING);
        rr.setReference(references.next());
        // pending_marker = order_id while PENDING; UNIQUE on this column
        // enforces "one open request per order" across both Postgres and H2.
        rr.setPendingMarker(order.getId());

        try {
            // saveAndFlush, not save: the id is application-assigned (@GeneratedValue UUID), so
            // a plain persist would defer the INSERT to commit and every constraint below would
            // surface OUTSIDE this try — as an opaque 500 — instead of being classified here.
            rr = requests.saveAndFlush(rr);
        } catch (DataIntegrityViolationException violation) {
            throw classifySubmitViolation(violation);
        }

        token.setConsumedAt(Times.nowMicros());
        tokens.save(token);

        publisher.publishEvent(new RefundRequestSubmittedEvent(rr.getId()));
        log.info("[refund-request] issued requestId={} reference={} orderId={}",
            rr.getId(), rr.getReference(), order.getId());
        return new PublicRefundSubmitResponse(
            rr.getId(), rr.getReference(),
            rr.getStatus().name().toLowerCase(Locale.ROOT), rr.getCreatedAt());
    }

    /** {@code UNIQUE(pending_marker)} — "one open refund request per order" (V30). */
    static final String PENDING_MARKER_CONSTRAINT = "uq_refund_requests_one_open_per_order";
    /** {@code UNIQUE(reference)} — the buyer-facing code (V81). */
    static final String REFERENCE_CONSTRAINT = "uq_refund_requests_reference";

    /**
     * Turns an integrity violation on submit into the truth about which rule was broken.
     *
     * <p>The old code assumed every violation here was the pending-marker race and answered
     * {@code 409 REFUND_REQUEST_ALREADY_OPEN} unconditionally. That is a claim about the
     * buyer's own order, and it was wrong for at least two reachable causes: a reference
     * collision (rare, but the whole point of the UNIQUE index is that it happens), and — during
     * a Railway deploy overlap, where the new container's Flyway run and the old container's
     * traffic coexist — a NOT NULL the old code could not satisfy. Both told a buyer on the
     * payments path that they already had a request open when they did not, which is the kind of
     * message that ends in a chargeback rather than a retry.
     *
     * <p>Anything unrecognised is rethrown rather than guessed at: an unmapped 500 is honest,
     * a confidently wrong 409 is not.
     */
    private static RuntimeException classifySubmitViolation(DataIntegrityViolationException violation) {
        if (mentions(violation, PENDING_MARKER_CONSTRAINT)) {
            return new ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.REFUND_REQUEST_ALREADY_OPEN,
                "A refund request is already open for this order");
        }
        if (mentions(violation, REFERENCE_CONSTRAINT)) {
            // Two submits drew the same code. Nothing is wrong with the order — retrying
            // in-transaction is impossible (the transaction is already doomed), so ask for a
            // fresh attempt instead of blaming the buyer's order for it.
            log.warn("[refund-request] reference collision on submit — buyer asked to retry");
            return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.INTERNAL,
                "Could not allocate a refund reference, please retry");
        }
        return violation;
    }

    /**
     * Whether {@code name} appears anywhere in the exception chain — either as Hibernate's
     * parsed constraint name or in a driver message. Both are checked because the two engines
     * report it differently (Postgres lower-case and bare, H2 upper-case and schema-qualified)
     * and because Hibernate cannot always parse a name out at all.
     */
    private static boolean mentions(Throwable t, String name) {
        String needle = name.toLowerCase(Locale.ROOT);
        for (Throwable c = t; c != null; c = (c.getCause() == c ? null : c.getCause())) {
            if (c instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String constraint = cve.getConstraintName();
                if (constraint != null && constraint.toLowerCase(Locale.ROOT).contains(needle)) {
                    return true;
                }
            }
            String message = c.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }

    @Transactional(readOnly = true)
    public RefundRequestDetailResponse getRequest(UUID id, UUID orgId) {
        RefundRequest rr = requests.findByIdAndOrgId(id, orgId)
            .orElseThrow(() -> ApiException.notFound("RefundRequest"));

        Order order = orders.findById(rr.getOrderId())
            .orElseThrow(() -> ApiException.notFound("Order"));

        List<Ticket> all = tickets.findByOrderId(order.getId());
        Set<UUID> alreadyRefunded = all.isEmpty()
            ? Set.of()
            : refundTickets.findRefundedTicketIds(all.stream().map(Ticket::getId).toList());
        List<Ticket> refundable = all.stream()
            .filter(t -> !alreadyRefunded.contains(t.getId()))
            .filter(t -> !Ticket.STATE_REDEEMED.equals(t.getState()))
            .toList();

        Map<UUID, String> tierNames = new HashMap<>();
        for (Ticket t : all) {
            tierNames.computeIfAbsent(t.getTierId(), id2 -> tiers.findById(id2)
                .map(TicketTier::getName).orElse(""));
        }

        ProposedRefundResponse proposed = null;
        if (rr.getStatus() == RefundRequestStatus.PENDING && !refundable.isEmpty()) {
            long amount = refundService.computeRefundAmountMinor(order, refundable);
            long appFee = refundService.computeAppFeeRefundMinor(order, amount);
            proposed = new ProposedRefundResponse(
                amount, appFee, order.getCurrency(),
                refundable.stream().map(Ticket::getId).toList());
        }

        // refundStatus left null; the controller layer may enrich if needed.
        String refundStatus = null;

        return new RefundRequestDetailResponse(
            rr.getId(),
            rr.getReference(),
            order.getId(),
            order.getEventId(),
            null, // eventName left null for MVP
            rr.getBuyerEmail(),
            rr.getBuyerPhone(),
            rr.getStatus().name().toLowerCase(Locale.ROOT),
            rr.getReason().toWire(),
            rr.getExplanation(),
            rr.getDecisionNote(),
            rr.getCreatedAt(),
            rr.getDecidedAt(),
            all.stream()
                .map(t -> new RefundRequestDetailResponse.TicketLine(
                    t.getId(), tierNames.getOrDefault(t.getTierId(), ""),
                    t.getPriceMinor(), t.getState()))
                .toList(),
            proposed,
            rr.getRefundId(),
            refundStatus
        );
    }

    @Transactional(readOnly = true)
    public List<RefundRequestSummaryResponse> listRequests(
            UUID orgId,
            UUID eventId,
            List<RefundRequestStatus> statuses,
            int limit) {
        return listRequests(orgId, eventId, statuses, null, limit);
    }

    /**
     * Operator list, optionally narrowed by {@code search}: a refund reference the
     * customer quoted ({@code REQ-8K2M-26}, or just {@code 8K2M-26}) or part of their
     * email address.
     *
     * <p>Two repository methods rather than one nullable-parameter query — a null String
     * flowing into {@code lower}/{@code like} type-infers to bytea on Postgres and 500s.
     * See {@link RefundRequestRepository#pageSearch}.
     */
    @Transactional(readOnly = true)
    public List<RefundRequestSummaryResponse> listRequests(
            UUID orgId,
            UUID eventId,
            List<RefundRequestStatus> statuses,
            String search,
            int limit) {

        PageRequest pageReq = PageRequest.of(0, Math.min(100, Math.max(1, limit)));
        List<RefundRequestStatus> effectiveStatuses =
            (statuses == null || statuses.isEmpty())
                ? List.of(RefundRequestStatus.values())
                : statuses;

        String term = (search == null || search.isBlank()) ? null : search.trim();
        List<RefundRequest> rows;
        if (term == null) {
            rows = requests.page(orgId, eventId, effectiveStatuses, pageReq);
        } else {
            // Reference lookups are case/prefix forgiving, so the term is normalised for the
            // reference comparison — but the EMAIL comparison always gets the term as typed.
            // Sending only the normalised form lost the original whenever an email fragment
            // happened to be reference-shaped: searching "mark-22" became "REQ-MARK-22" and
            // stopped matching mark-22@example.com.
            String normalizedRef = RefundReferenceGenerator.normalize(term);
            rows = requests.pageSearch(orgId, eventId, effectiveStatuses,
                normalizedRef != null ? normalizedRef : term, term, pageReq);
        }

        return rows.stream().map(rr -> {
            // ticketCount and estimatedRefundMinor are best-effort live
            // computations. We accept the per-row cost for now and add caching
            // if it bites.
            List<Ticket> all = tickets.findByOrderId(rr.getOrderId());
            Set<UUID> alreadyRefunded = all.isEmpty()
                ? Set.of()
                : refundTickets.findRefundedTicketIds(all.stream().map(Ticket::getId).toList());
            List<Ticket> refundable = all.stream()
                .filter(t -> !alreadyRefunded.contains(t.getId()))
                .filter(t -> !Ticket.STATE_REDEEMED.equals(t.getState()))
                .toList();
            long estimated = 0;
            String currency = null;
            Order order = orders.findById(rr.getOrderId()).orElse(null);
            if (order != null && !refundable.isEmpty()) {
                estimated = refundService.computeRefundAmountMinor(order, refundable);
                currency = order.getCurrency();
            }
            return new RefundRequestSummaryResponse(
                rr.getId(), rr.getReference(), rr.getOrderId(), rr.getEventId(), null,
                rr.getBuyerEmail(),
                rr.getStatus().name().toLowerCase(Locale.ROOT),
                rr.getReason().toWire(),
                rr.getCreatedAt(),
                rr.getDecidedAt(),
                refundable.size(),
                estimated,
                currency,
                rr.getRefundId(),
                null);
        }).toList();
    }

    @Transactional
    public RefundRequestDecisionResponse approveRequest(
            UUID id, AuthPrincipal principal, RefundRequestApproveRequest body) {

        RefundRequest rr = requests.findByIdAndOrgId(id, principal.orgId())
            .orElseThrow(() -> ApiException.notFound("RefundRequest"));

        if (rr.getStatus() != RefundRequestStatus.PENDING) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.REFUND_REQUEST_NOT_PENDING,
                "Refund request is not pending");
        }

        Order order = orders.findById(rr.getOrderId())
            .orElseThrow(() -> ApiException.notFound("Order"));

        List<Ticket> all = tickets.findByOrderId(order.getId());
        Set<UUID> alreadyRefunded = all.isEmpty()
            ? Set.of()
            : refundTickets.findRefundedTicketIds(all.stream().map(Ticket::getId).toList());
        List<Ticket> refundable = all.stream()
            .filter(t -> !alreadyRefunded.contains(t.getId()))
            .filter(t -> !Ticket.STATE_REDEEMED.equals(t.getState()))
            .toList();
        if (refundable.isEmpty()) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.NO_REFUNDABLE_TICKETS,
                "No refundable tickets remain on this order");
        }

        long amount = refundService.computeRefundAmountMinor(order, refundable);
        long appFee = refundService.computeAppFeeRefundMinor(order, amount);

        if (!body.confirm()) {
            // Defensive backstop: typical clients read live proposedRefund from
            // GET /{id} and pass confirm:true. When confirm:false we return the
            // live numbers in the error body so the dashboard can re-render.
            ProposedRefundResponse proposed = new ProposedRefundResponse(
                amount, appFee, order.getCurrency(),
                refundable.stream().map(Ticket::getId).toList());
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                ErrorCode.REFUND_APPROVAL_NOT_CONFIRMED,
                "Set confirm:true to issue the refund.",
                Map.of("proposedRefund", proposed.toString()));
        }

        // Deterministic idempotency key — a re-press of Confirm is a no-op.
        Refund stripeRefund = refundService.createRefund(
            order.getId(), principal,
            "refund-request-" + rr.getId(),
            refundable.stream().map(Ticket::getId).toList(),
            rr.getReason().toStripeReason());

        rr.setStatus(RefundRequestStatus.APPROVED);
        rr.setDecidedAt(Times.nowMicros());
        rr.setDecidedByUserId(principal.userId());
        rr.setDecisionNote(body.note());
        rr.setRefundId(stripeRefund.getId());
        // Release the "one open per order" slot enforced by UNIQUE(pending_marker).
        rr.setPendingMarker(null);
        requests.save(rr);

        log.info("[refund-request] approved id={} refundId={} by={}",
            rr.getId(), stripeRefund.getId(), principal.userId());
        return new RefundRequestDecisionResponse(
            "approved", stripeRefund.getId(),
            stripeRefund.getStatus() == null
                ? null
                : stripeRefund.getStatus().name().toLowerCase(Locale.ROOT));
    }

    @Transactional
    public RefundRequestDecisionResponse rejectRequest(
            UUID id, AuthPrincipal principal, RefundRequestRejectRequest body) {

        RefundRequest rr = requests.findByIdAndOrgId(id, principal.orgId())
            .orElseThrow(() -> ApiException.notFound("RefundRequest"));

        if (rr.getStatus() != RefundRequestStatus.PENDING) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.REFUND_REQUEST_NOT_PENDING,
                "Refund request is not pending");
        }

        rr.setStatus(RefundRequestStatus.REJECTED);
        rr.setDecidedAt(Times.nowMicros());
        rr.setDecidedByUserId(principal.userId());
        rr.setDecisionNote(body.note());
        // Release the "one open per order" slot enforced by UNIQUE(pending_marker).
        rr.setPendingMarker(null);
        requests.save(rr);

        publisher.publishEvent(new RefundRequestRejectedEvent(rr.getId()));
        log.info("[refund-request] rejected id={} by={} noteLen={}",
            rr.getId(), principal.userId(),
            body.note() == null ? 0 : body.note().length());
        return new RefundRequestDecisionResponse("rejected", null, null);
    }

    // ---------- helpers ----------

    private String baseUrl() {
        String base = emailProps.getBuyerSiteBaseUrl();
        if (base != null && base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base == null ? "" : base;
    }

    private static String generateRawToken() {
        byte[] buf = new byte[32];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            return "";
        }
    }

    private static String hashIp(String ip) {
        return sha256Hex(ip);
    }
}

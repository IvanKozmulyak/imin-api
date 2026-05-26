package com.imin.iminapi.refund;

import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.OrderRecoveryAttempt;
import com.imin.iminapi.repository.OrderRecoveryAttemptRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.service.ticket.TicketProperties;
import com.imin.iminapi.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private final OrderRecoveryAttemptRepository attempts;
    private final RefundRequestTokenRepository tokens;
    private final RefundRequestRepository requests;
    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties emailProps;
    private final TicketProperties ticketProps;
    private final ApplicationEventPublisher publisher;

    public RefundRequestService(OrderRepository orders,
                                OrderRecoveryAttemptRepository attempts,
                                RefundRequestTokenRepository tokens,
                                RefundRequestRepository requests,
                                EmailService email,
                                EmailTemplateRenderer renderer,
                                EmailProperties emailProps,
                                TicketProperties ticketProps,
                                ApplicationEventPublisher publisher) {
        this.orders = orders;
        this.attempts = attempts;
        this.tokens = tokens;
        this.requests = requests;
        this.email = email;
        this.renderer = renderer;
        this.emailProps = emailProps;
        this.ticketProps = ticketProps;
        this.publisher = publisher;
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

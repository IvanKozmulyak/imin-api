package com.imin.iminapi.service.ticket;

import com.imin.iminapi.security.IpHasher;
import com.imin.iminapi.util.LogSafe;
import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.email.EmailProperties;
import com.imin.iminapi.email.EmailService;
import com.imin.iminapi.email.EmailTemplateRenderer;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.OrderRecoveryAttempt;
import com.imin.iminapi.repository.OrderRecoveryAttemptRepository;
import com.imin.iminapi.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Self-service recovery: a buyer who lost their confirmation email can ask us
 * to resend it by email address. Always returns 204 to the caller regardless
 * of outcome so an attacker can't probe whether an email purchased an event.
 * Rate-limited per (email, hour) and (ip, hour).
 */
@Service
public class OrderRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(OrderRecoveryService.class);

    private final OrderRepository orders;
    private final EmailService email;
    private final EmailTemplateRenderer renderer;
    private final EmailProperties emailProps;
    private final TicketProperties ticketProps;
    private final OrderRecoveryAttemptRepository attempts;
    private final IpHasher ipHasher;

    public OrderRecoveryService(OrderRepository orders,
                                 EmailService email,
                                 EmailTemplateRenderer renderer,
                                 EmailProperties emailProps,
                                 TicketProperties ticketProps,
                                 OrderRecoveryAttemptRepository attempts,
                                 IpHasher ipHasher) {
        this.orders = orders;
        this.email = email;
        this.renderer = renderer;
        this.emailProps = emailProps;
        this.ticketProps = ticketProps;
        this.attempts = attempts;
        this.ipHasher = ipHasher;
    }

    /**
     * <b>Deliberately NOT {@code @Transactional}.</b> The Resend send at the end is a
     * synchronous outbound HTTP call, and this endpoint is unauthenticated with no
     * rate-limit bucket — its only cap is the in-DB counter below. Wrapping the method
     * pinned a pooled JDBC connection (prod max 20) for the duration of a third party's
     * round trip, on a path an attacker picks the rate of. Nothing here needs a shared
     * atomic unit: the only write is the attempt row, which {@code attempts.save()}
     * commits in its own repository transaction, and it must survive on its own anyway
     * so a failed lookup still counts against the limit. Same reasoning, same emailer,
     * as {@code BuyerOrderActionsController}'s deliberately-outside-the-transaction send.
     */
    public void requestRecovery(String rawEmail, UUID eventIdOrNull, String clientIp) {
        if (rawEmail == null) return;
        String normalized = rawEmail.trim().toLowerCase(Locale.ROOT);

        // Log the attempt up-front: even invalid inputs count against the rate
        // limit so attackers can't burn the limit on us for free.
        recordAttempt(normalized, clientIp);

        if (normalized.isEmpty() || !normalized.contains("@")) {
            return;
        }

        Instant rateCutoff = Instant.now().minus(Duration.ofHours(1));
        long byEmail = attempts.countByEmailAndAttemptedAtAfter(normalized, rateCutoff);
        long byIp = attempts.countByIpHashAndAttemptedAtAfter(hashIp(clientIp), rateCutoff);
        int cap = ticketProps.getRecoveryMaxPerHour();
        if (byEmail > cap || byIp > cap) {
            log.info("Recovery rate-limited (email={} byEmail={} byIp={})",
                    LogSafe.email(normalized), byEmail, byIp);
            return;
        }

        Instant recoveryCutoff = Instant.now()
                .minus(Duration.ofDays(ticketProps.getRecoveryWindowDays()));
        List<Order> found = orders.findRecentForRecovery(normalized, eventIdOrNull, recoveryCutoff);
        if (found.isEmpty()) {
            log.info("Recovery: no orders found for {}", LogSafe.email(normalized));
            return;
        }

        String base = baseUrl();
        StringBuilder linksHtml = new StringBuilder();
        StringBuilder linksText = new StringBuilder();
        for (Order o : found) {
            String url = base + "/order/" + o.getToken();
            linksHtml.append("<li><a href=\"").append(url)
                    .append("\" style=\"color:#0a66c2;\">").append(url).append("</a></li>");
            linksText.append("- ").append(url).append('\n');
        }

        // One mail can span several orders in different languages; there is no "the"
        // locale. Use the most recent order's — findRecentForRecovery is newest-first, so
        // that is the buyer's latest expressed preference. Null ⇒ English.
        String locale = found.get(0).getBuyerLocale();

        // Renderer escapes by default; sidestep with placeholder + post-render replace.
        Map<String, String> values = new LinkedHashMap<>();
        values.put("links", "__LINKS_PLACEHOLDER__");
        EmailTemplateRenderer.Rendered r = renderer.render("order-recovery", locale, values);
        String html = r.html().replace("__LINKS_PLACEHOLDER__", linksHtml.toString());
        String text = r.text().replace("__LINKS_PLACEHOLDER__", linksText.toString());

        String subject = EmailLocale.choose(locale,
                "Recover your tickets · imin",
                "Recupera tus entradas · imin",
                "Récupérez vos billets · imin",
                "Відновлення ваших квитків · imin");

        try {
            email.send(normalized, subject, html, text);
            log.info("Recovery: sent {} order link(s) to {}", found.size(), LogSafe.email(normalized));
        } catch (Exception e) {
            log.warn("Recovery email failed for {}: {}", LogSafe.email(normalized), LogSafe.redact(e.getMessage()));
        }
    }

    private void recordAttempt(String emailNormalized, String clientIp) {
        OrderRecoveryAttempt a = new OrderRecoveryAttempt();
        a.setEmail(emailNormalized == null ? "" : emailNormalized);
        a.setIpHash(hashIp(clientIp));
        attempts.save(a);
    }

    /**
     * Keyed, not a bare digest: IPv4 is 2³² values, so an unsalted SHA-256 is a
     * reversible record of who asked about which order. See {@link IpHasher}.
     */
    private String hashIp(String ip) {
        return ipHasher.hash(ip);
    }

    private String baseUrl() {
        String base = emailProps.getBuyerSiteBaseUrl();
        if (base != null && base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base == null ? "" : base;
    }
}

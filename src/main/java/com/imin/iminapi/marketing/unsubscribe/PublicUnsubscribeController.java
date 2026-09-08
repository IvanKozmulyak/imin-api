package com.imin.iminapi.marketing.unsubscribe;

import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * Owned opt-out endpoint (spec §2.4/§3/§7). Shared by email List-Unsubscribe headers +
 * footer AND the SMS opt-out URL suffix. Both verbs live on one URL, and the split
 * between them is load-bearing:
 *
 * <ul>
 *   <li><b>POST</b> mutates. It is what RFC 8058 one-click sends and what the
 *       confirm page below submits.</li>
 *   <li><b>GET</b> renders the confirm page and touches nothing.</li>
 * </ul>
 *
 * <p>The GET used to write the opt-out too, on the reasoning that the write is
 * idempotent. Idempotence is not the problem: a footer link is fetched by things
 * that are not the recipient — mail-client image proxies, link prefetchers,
 * corporate URL scanners, security sandboxes — so people were unsubscribed by
 * software on their behalf, and the consent record recorded them as the actor.
 * A safe method must be safe.
 *
 * <p>The token never expires (see {@link UnsubscribeTokenService}), so the page
 * works whenever the recipient gets round to it. Our DB is the sole suppression
 * authority.
 */
@RestController
@RequestMapping("/api/v1/public/unsubscribe")
public class PublicUnsubscribeController {

    private final UnsubscribeTokenService tokens;
    private final ConsentService consentService;
    private final RateLimiter rateLimiter;

    public PublicUnsubscribeController(UnsubscribeTokenService tokens, ConsentService consentService,
                                       RateLimiter rateLimiter) {
        this.tokens = tokens;
        this.consentService = consentService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/{token}")
    @ResponseBody
    public String oneClick(@PathVariable String token, HttpServletRequest http) {
        // Per client IP. Generous: a mail client may fire one-click for several
        // messages at once, and an opt-out is the one request we must never make
        // hard to complete. It bounds signature-probing, not real recipients.
        rateLimiter.consume("unsubscribe", "ip:" + http.getRemoteAddr());
        UnsubscribeTokenService.Claims claims = tokens.verify(token)
                .orElseThrow(() -> ApiException.notFound("Unsubscribe link"));
        // The recipient acted on this sender themselves — their mail client's
        // unsubscribe control (RFC 8058) or the confirm button below. As explicit
        // a decision about one sender as exists, so it is sticky (§6.3).
        consentService.unsubscribe(claims.orgId(), claims.membershipId(),
                "one_click", claims.channel(), ConsentOrigin.DATA_SUBJECT, null);
        return "unsubscribed";
    }

    /**
     * The confirm page. Verifies the token so a bad link still 404s without
     * pretending, then renders a single POST button back to this same URL.
     */
    @GetMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String confirmPage(@PathVariable String token, HttpServletRequest http) {
        rateLimiter.consume("unsubscribe", "ip:" + http.getRemoteAddr());
        tokens.verify(token).orElseThrow(() -> ApiException.notFound("Unsubscribe link"));
        String action = HtmlUtils.htmlEscape("/api/v1/public/unsubscribe/" + token);
        return """
                <!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="robots" content="noindex">
                <title>Unsubscribe</title></head>
                <body style="font-family:sans-serif;text-align:center;padding:48px;color:#11091f;">
                <h1 style="font-size:24px;margin:0 0 12px;">Unsubscribe?</h1>
                <p style="margin:0 0 24px;color:#4a4458;">
                  Confirm and you will stop receiving marketing messages from this organizer.
                </p>
                <form method="post" action="%s">
                  <button type="submit"
                          style="font:inherit;font-weight:700;padding:12px 24px;border:0;border-radius:8px;
                                 background:#2d5cff;color:#fff;cursor:pointer;">
                    Yes, unsubscribe me
                  </button>
                </form>
                </body></html>
                """.formatted(action);
    }
}

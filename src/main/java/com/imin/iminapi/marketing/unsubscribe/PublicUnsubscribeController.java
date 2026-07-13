package com.imin.iminapi.marketing.unsubscribe;

import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.security.ApiException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owned opt-out endpoint (spec §2.4/§3/§7). Shared by email List-Unsubscribe headers +
 * footer AND the SMS opt-out URL suffix. POST = RFC 8058 one-click; GET = human
 * confirmation page. Both resolve the signed token to (org, membership, channel) and
 * write through the channel-aware ConsentService. Our DB is the sole suppression authority.
 */
@RestController
@RequestMapping("/api/v1/public/unsubscribe")
public class PublicUnsubscribeController {

    private final UnsubscribeTokenService tokens;
    private final ConsentService consentService;

    public PublicUnsubscribeController(UnsubscribeTokenService tokens, ConsentService consentService) {
        this.tokens = tokens;
        this.consentService = consentService;
    }

    @PostMapping("/{token}")
    @ResponseBody
    public String oneClick(@PathVariable String token) {
        UnsubscribeTokenService.Claims claims = tokens.verify(token)
                .orElseThrow(() -> ApiException.notFound("Unsubscribe link"));
        consentService.unsubscribe(claims.orgId(), claims.membershipId(),
                "one_click", claims.channel(), null);
        return "unsubscribed";
    }

    @GetMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String confirmPage(@PathVariable String token) {
        UnsubscribeTokenService.Claims claims = tokens.verify(token)
                .orElseThrow(() -> ApiException.notFound("Unsubscribe link"));
        // GET also honors the opt-out (footer-link click) — same channel-aware write.
        consentService.unsubscribe(claims.orgId(), claims.membershipId(),
                "footer_link", claims.channel(), null);
        return "<!DOCTYPE html><html><body style=\"font-family:sans-serif;text-align:center;padding:48px;\">"
                + "<h1>You are unsubscribed</h1>"
                + "<p>You will no longer receive marketing messages from this organizer.</p>"
                + "</body></html>";
    }
}

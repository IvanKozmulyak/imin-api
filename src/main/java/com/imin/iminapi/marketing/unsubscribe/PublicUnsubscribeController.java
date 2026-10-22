package com.imin.iminapi.marketing.unsubscribe;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.ConsentOrigin;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.audience.service.ConsentService;
import com.imin.iminapi.checkoutreminder.service.CheckoutReminderService;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import com.imin.iminapi.checkoutreminder.model.CheckoutReminder;
import java.util.Optional;
import java.util.UUID;

/**
 * Owned opt-out endpoint. The consent write lives here, and this controller is the sole
 * suppression authority. The email footer links to the human page on the buyer site; the
 * {@code List-Unsubscribe} header (RFC 8058 one-tap) posts straight here.
 *
 * <ul>
 *   <li><b>POST</b> mutates: RFC 8058 one-click, and what the buyer-site page submits.
 *       Test-email tokens answer 200 and write nothing. A token that fails verification costs the per-IP
 *       {@code unsubscribe} bucket and answers 404; a verified one only its own {@code unsubscribe-token} bucket
 *       (10 per 5 min), so a mail provider's shared IP is never refused.</li>
 *   <li><b>GET /{token}/details</b> reads state for the buyer-site page; it writes nothing.</li>
 *   <li><b>GET /{token}</b> redirects (303) to the buyer-site page and touches nothing: a footer link
 *       is fetched by mail-client proxies and URL scanners, so a safe method must stay safe.</li>
 * </ul>
 *
 * <p>The token never expires (see {@link UnsubscribeTokenService}).
 * A checkout-reminder token resolves to (organizer, address) instead of a membership, with the
 * same responses, plus a 500 when its opt-out could not be recorded, so the mail client retries.
 */
@RestController
@RequestMapping("/api/v1/public/unsubscribe")
public class PublicUnsubscribeController {

    /** The literal older test sends signed into their footer link. */
    private static final String LEGACY_TEST_TOKEN = "preview";

    private static final Logger log = LoggerFactory.getLogger(PublicUnsubscribeController.class);

    private final UnsubscribeTokenService tokens;
    private final ConsentService consentService;
    private final RateLimiter rateLimiter;
    private final CampaignRecipientRepository campaignRecipients;
    private final CampaignRepository campaigns;
    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;
    private final MarketingEmailProperties emailProps;
    private final CheckoutReminderService checkoutReminders;

    public PublicUnsubscribeController(UnsubscribeTokenService tokens, ConsentService consentService,
                                       RateLimiter rateLimiter,
                                       CampaignRecipientRepository campaignRecipients,
                                       CampaignRepository campaigns, MembershipRepository memberships,
                                       OrganizationRepository organizations, MarketingEmailProperties emailProps,
                                       CheckoutReminderService checkoutReminders) {
        this.campaigns = campaigns;
        this.memberships = memberships;
        this.organizations = organizations;
        this.emailProps = emailProps;
        this.tokens = tokens;
        this.consentService = consentService;
        this.rateLimiter = rateLimiter;
        this.campaignRecipients = campaignRecipients;
        this.checkoutReminders = checkoutReminders;
    }

    @PostMapping("/{token}")
    @ResponseBody
    public String oneClick(@PathVariable String token, HttpServletRequest http) {
        // A test email's link, signed or the legacy literal, unsubscribes nobody.
        boolean test = LEGACY_TEST_TOKEN.equals(token) || tokens.verifyTest(token).isPresent();
        Optional<UUID> reminderId = test ? Optional.empty() : tokens.verifyReminder(token);
        Optional<UnsubscribeTokenService.Claims> verified = test || reminderId.isPresent()
                ? Optional.empty() : tokens.verify(token);
        if (!test && reminderId.isEmpty() && verified.isEmpty()) {
            // Only a link that fails verification costs the per-IP budget: it bounds signature probing.
            rateLimiter.consume("unsubscribe", "ip:" + http.getRemoteAddr());
            throw ApiException.notFound("Unsubscribe link");
        }
        // A verified link is never limited per IP: one mail provider IP fires RFC 8058 one-click for many fans after
        // a big send. Only replaying the same link many times is refused.
        rateLimiter.consume("unsubscribe-token", "token:" + tokenKey(token));
        if (test) return "test";
        if (reminderId.isPresent()) {
            // Sticky opt-out from this organizer, plus the membership's unsubscribe when there is one.
            checkoutReminders.unsubscribe(reminderId.get());
            return "unsubscribed";
        }
        UnsubscribeTokenService.Claims claims = verified.get();
        // The recipient acted on this sender themselves — their mail client's
        // unsubscribe control (RFC 8058) or the confirm button below. As explicit
        // a decision about one sender as exists, so it is sticky (§6.3).
        consentService.unsubscribe(claims.orgId(), claims.membershipId(),
                "one_click", claims.channel(), ConsentOrigin.DATA_SUBJECT, null);
        projectOntoCampaignRow(claims);
        return "unsubscribed";
    }

    /** A SHA-256 prefix of the token: the bucket key, so the token itself never reaches the rate-limit store. */
    private static String tokenKey(String token) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Record the opt-out on the campaign_recipients row the token came from, so the
     * campaign's {@code unsubscribed} stat is a real count instead of a structural 0
     * (nothing else in the tree ever writes that status). Strictly best-effort and
     * failure-isolated: the consent record is the authority and an opt-out must never
     * fail because a stat could not be projected.
     */
    private void projectOntoCampaignRow(UnsubscribeTokenService.Claims claims) {
        if (claims.campaignId() == null || claims.membershipId() == null) return;
        try {
            campaignRecipients.markUnsubscribed(
                    claims.campaignId(), claims.membershipId(), Instant.now());
        } catch (RuntimeException e) {
            log.warn("[unsubscribe] could not project opt-out onto campaign {}: {}",
                    claims.campaignId(), e.getMessage());
        }
    }

    /**
     * What the buyer-site page shows for this link. Reads only: nothing is written and no rate-limit
     * bucket is consumed, because the page fetches it server-side from a few shared egress IPs. The
     * token is HMAC-gated, and the response carries no fan name or address.
     */
    @GetMapping(value = "/{token}/details", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UnsubscribeDetails> details(@PathVariable String token) {
        UnsubscribeDetails body;
        if (LEGACY_TEST_TOKEN.equals(token)) {
            body = new UnsubscribeDetails("test", null, null, null);
        } else if (tokens.verifyTest(token).isPresent()) {
            UnsubscribeTokenService.TestClaims t = tokens.verifyTest(token).get();
            Campaign c = campaigns.findByIdAndOrgId(t.campaignId(), t.orgId()).orElse(null);
            body = new UnsubscribeDetails("test", "email", organizerName(t.orgId()), c == null ? null : c.getLocale());
        } else if (tokens.verifyReminder(token).isPresent()) {
            CheckoutReminder r = checkoutReminders.find(tokens.verifyReminder(token).get())
                    .orElseThrow(() -> ApiException.notFound("Unsubscribe link"));
            body = new UnsubscribeDetails(r.getUnsubscribedAt() != null ? "unsubscribed" : "subscribed", "email",
                    organizerName(r.getOrgId()), r.getBuyerLocale());
        } else {
            UnsubscribeTokenService.Claims claims = tokens.verify(token)
                    .orElseThrow(() -> ApiException.notFound("Unsubscribe link"));
            body = new UnsubscribeDetails(consentState(claims), claims.channel(),
                    organizerName(claims.orgId()), campaignLocale(claims));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** A missing membership (erased) counts as already unsubscribed. */
    private String consentState(UnsubscribeTokenService.Claims claims) {
        Membership m = claims.membershipId() == null ? null
                : memberships.findByIdAndOrgId(claims.membershipId(), claims.orgId()).orElse(null);
        if (m == null) return "unsubscribed";
        String status = "sms".equals(claims.channel()) ? m.getSmsConsentStatus() : m.getConsentStatus();
        return "unsubscribed".equals(status) ? "unsubscribed" : "subscribed";
    }

    private String organizerName(UUID orgId) {
        return organizations.findById(orgId).map(Organization::displayName).orElse(null);
    }

    private String campaignLocale(UnsubscribeTokenService.Claims claims) {
        if (claims.campaignId() == null) return null;
        return campaigns.findByIdAndOrgId(claims.campaignId(), claims.orgId()).map(Campaign::getLocale).orElse(null);
    }

    /**
     * Links in emails already sent, and clients that open the header URL, land on the human page on the
     * buyer site. Nothing is read or written here, so no bucket is consumed.
     */
    @GetMapping("/{token}")
    public ResponseEntity<Void> redirectToPage(@PathVariable String token) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(emailProps.unsubscribePageUrl(token)))
                .build();
    }
}

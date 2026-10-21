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
 *       Test-email tokens answer 200 and write nothing.</li>
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
        // Per client IP. Generous: a mail client may fire one-click for several
        // messages at once, and an opt-out is the one request we must never make
        // hard to complete. It bounds signature-probing, not real recipients.
        rateLimiter.consume("unsubscribe", "ip:" + http.getRemoteAddr());
        // A test email's link, signed or the legacy literal, unsubscribes nobody.
        if (LEGACY_TEST_TOKEN.equals(token) || tokens.verifyTest(token).isPresent()) return "test";
        Optional<UUID> reminderId = tokens.verifyReminder(token);
        if (reminderId.isPresent()) {
            // Sticky opt-out from this organizer, plus the membership's unsubscribe when there is one.
            checkoutReminders.unsubscribe(reminderId.get());
            return "unsubscribed";
        }
        UnsubscribeTokenService.Claims claims = tokens.verify(token)
                .orElseThrow(() -> ApiException.notFound("Unsubscribe link"));
        // The recipient acted on this sender themselves — their mail client's
        // unsubscribe control (RFC 8058) or the confirm button below. As explicit
        // a decision about one sender as exists, so it is sticky (§6.3).
        consentService.unsubscribe(claims.orgId(), claims.membershipId(),
                "one_click", claims.channel(), ConsentOrigin.DATA_SUBJECT, null);
        projectOntoCampaignRow(claims);
        return "unsubscribed";
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

package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Opt-out for a "notify me when tickets release" subscription.
 *
 * <h2>Why this has to exist</h2>
 *
 * <p>Anyone can subscribe to a drop alert without an account, and until now the
 * notify-me emails carried no unsubscribe at all: removing the subscription
 * required signing in, which a guest subscriber cannot do. A standing request
 * for mail that the recipient cannot withdraw is not a lawful one (CPCE L34-5).
 *
 * <p>The token is an HMAC over the subscription id, signed with the same key as
 * the marketing opt-out and, like it, with no expiry. It authorises exactly one
 * thing — deleting one subscription row — so it is worth nothing to anyone who
 * intercepts it beyond stopping mail the holder did not want.
 *
 * <p>POST only: a GET here would be fetched by mail-client image proxies and
 * link prefetchers, which is the same defect the marketing confirm page fixes.
 * The human-facing page lives on the buyer site at
 * {@code {buyerSiteBase}/notify/unsubscribe/{token}} and POSTs here.
 */
@RestController
@RequestMapping("/api/v1/public/notify/unsubscribe")
public class PublicNotifyUnsubscribeController {

    private final UnsubscribeTokenService tokens;
    private final NotifySubscriptionRepository subscriptions;
    private final RateLimiter rateLimiter;

    public PublicNotifyUnsubscribeController(UnsubscribeTokenService tokens,
                                             NotifySubscriptionRepository subscriptions,
                                             RateLimiter rateLimiter) {
        this.tokens = tokens;
        this.subscriptions = subscriptions;
        this.rateLimiter = rateLimiter;
    }

    /**
     * @return 204 whether or not a row was there to delete — a second click, or a
     *         retry, must look like success. A token neither key signed is 404.
     */
    @PostMapping("/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@PathVariable String token, HttpServletRequest http) {
        rateLimiter.consume("unsubscribe", "ip:" + http.getRemoteAddr());
        UUID subscriptionId = tokens.verifyNotify(token)
                .orElseThrow(() -> ApiException.notFound("Unsubscribe link"));
        subscriptions.deleteById(subscriptionId);
    }
}

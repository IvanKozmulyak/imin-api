package com.imin.iminapi.buyer.controller;

import com.imin.iminapi.buyer.dto.BuyerAddressRequests;
import com.imin.iminapi.buyer.dto.BuyerMeResponse;
import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.security.BuyerPrincipal;
import com.imin.iminapi.buyer.security.CurrentBuyer;
import com.imin.iminapi.buyer.service.BuyerAddressService;
import com.imin.iminapi.buyer.service.BuyerSessionService;
import com.imin.iminapi.security.RateLimiter;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The buyer's address set — {@code /api/v1/buyer/emails} (epic §2.3, §3.3).
 *
 * <p>Authenticated: covered by {@code SecurityConfig}'s
 * {@code .requestMatchers("/api/v1/buyer/**").hasRole("BUYER")}, so an
 * organizer bearer token cannot reach it either. Every response carries
 * {@code Cache-Control: private, no-store}.
 *
 * <p><b>Adding an address grants nothing.</b> The row lands unverified, and the
 * orders join reads only {@code verified_at IS NOT NULL}; the whole point of
 * this controller is the one screen where a buyer proves control of a second
 * mailbox so its past guest orders join their account. The proving happens in
 * {@code BuyerAddressClaims}, which is the single door across that boundary.
 *
 * <p>{@code POST /buyer/emails} is <b>204 on every branch</b> and writes the
 * pending row on every branch — see {@link BuyerAddressService} for why
 * neutrality on an authenticated endpoint has to survive the follow-up
 * {@code GET}, not just the POST.
 */
@RestController
public class BuyerEmailsController {

    private static final String NO_STORE = "private, no-store";

    private final BuyerAddressService addresses;
    private final BuyerSessionService sessions;
    private final RateLimiter rateLimiter;

    public BuyerEmailsController(BuyerAddressService addresses,
                                 BuyerSessionService sessions,
                                 RateLimiter rateLimiter) {
        this.addresses = addresses;
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/api/v1/buyer/emails")
    public ResponseEntity<List<BuyerMeResponse.Address>> list(@CurrentBuyer BuyerPrincipal buyer) {
        return ok(BuyerMeResponse.addresses(addresses.list(buyer.accountId())));
    }

    /**
     * Keyed on the <b>account id</b>, not the address: keying it on the address
     * would let one buyer burn a stranger's bucket, and the abuse being
     * throttled (mailing codes at arbitrary inboxes) is per-account anyway.
     * Five per hour is generous for a real profile screen and useless as a mail
     * cannon.
     */
    @PostMapping("/api/v1/buyer/emails")
    public ResponseEntity<Void> add(@CurrentBuyer BuyerPrincipal buyer,
                                    @Valid @RequestBody BuyerAddressRequests.AddAddress req) {
        rateLimiter.consume("buyer-email-add", "account:" + buyer.accountId());
        addresses.addAddress(buyer.accountId(), req.email());
        return noContent();
    }

    /**
     * Redeems the six-digit code. On success the address is verified and its
     * past orders are in {@code GET /buyer/orders} from the next call — that
     * transition is the entire feature.
     *
     * <p>Not bucket-limited: the DB-counted per-address lockout in
     * {@code BuyerEmailVerificationService} is the control, precisely because
     * {@code RateLimitConfig} is {@code @Profile("!test")} and a limit the suite
     * cannot assert on is a limit that regresses.
     */
    @PostMapping("/api/v1/buyer/emails/verify")
    public ResponseEntity<BuyerMeResponse> verify(@CurrentBuyer BuyerPrincipal buyer,
                                                  @Valid @RequestBody BuyerAddressRequests.VerifyAddress req) {
        BuyerAccount account = addresses.verifyAddress(buyer.accountId(), req.email(), req.code());
        return ok(BuyerMeResponse.of(account, addresses.list(account.getId())));
    }

    /**
     * Promotes an already-verified address to primary and revokes every session
     * (§2.2). The caller is signed out too, so the cookie is cleared in the same
     * response — otherwise the browser keeps sending a token the server has
     * already killed and the frontend sees an unexplained 401 on its next call.
     */
    @PostMapping("/api/v1/buyer/emails/primary")
    public ResponseEntity<Void> setPrimary(@CurrentBuyer BuyerPrincipal buyer,
                                           @Valid @RequestBody BuyerAddressRequests.SetPrimary req) {
        addresses.setPrimary(buyer.accountId(), req.email());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessions.clearCookie().toString())
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .build();
    }

    /**
     * Removes an address. Refuses on the primary and on the last verified one —
     * see {@code BuyerAddressService.remove} for the rule and its reasoning.
     *
     * <p>{@code {email:.+}} rather than {@code {email}}: without the regex the
     * path variable stops at the last dot, so {@code ada@example.com} would
     * arrive as {@code ada@example} and match nothing. The client URL-encodes the
     * address.
     */
    @DeleteMapping("/api/v1/buyer/emails/{email:.+}")
    public ResponseEntity<Void> remove(@CurrentBuyer BuyerPrincipal buyer,
                                       @PathVariable("email") String email) {
        addresses.remove(buyer.accountId(), email);
        return noContent();
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(body);
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, NO_STORE).build();
    }
}

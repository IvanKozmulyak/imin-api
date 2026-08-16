package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.ticket.AppleWalletPassService;
import com.imin.iminapi.service.ticket.QrImageRenderer;
import com.imin.iminapi.service.ticket.QrPayloadSigner;
import com.imin.iminapi.service.ticket.WalletEligibility;
import com.imin.iminapi.service.ticket.google.GoogleWalletPassService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-ticket asset endpoints — QR image, Apple Wallet pass, Google Wallet save
 * link. Token is the only auth: it's a 24-byte random base64 string,
 * unguessable, and arrived only via the buyer's email or order page.
 * {@code Cache-Control: private, no-store} so a shared CDN cache hit can't serve
 * one buyer's QR — or one buyer's save link — to another.
 *
 * <p>All three routes are covered by {@code SecurityConfig}'s blanket
 * {@code GET /api/v1/public/**} permit and none of them has a matcher of its
 * own. Verified rather than duplicated: a second matcher here would be a second
 * place to keep in sync, and {@code PublicTicketAssetControllerTest} boots the
 * real security chain so a change to that rule shows up as a 401/403 in the
 * suite rather than in production.
 */
@RestController
public class PublicTicketAssetController {

    private final TicketRepository tickets;
    private final QrPayloadSigner signer;
    private final QrImageRenderer renderer;
    private final AppleWalletPassService wallet;
    private final GoogleWalletPassService googleWallet;
    private final RateLimiter rateLimiter;

    public PublicTicketAssetController(TicketRepository tickets,
                                        QrPayloadSigner signer,
                                        QrImageRenderer renderer,
                                        AppleWalletPassService wallet,
                                        GoogleWalletPassService googleWallet,
                                        RateLimiter rateLimiter) {
        this.tickets = tickets;
        this.signer = signer;
        this.renderer = renderer;
        this.wallet = wallet;
        this.googleWallet = googleWallet;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping(value = "/api/v1/public/tickets/{token}/qr.png",
                produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qrPng(@PathVariable String token) {
        Ticket t = tickets.findByToken(token).orElseThrow(() -> ApiException.notFound("Ticket"));
        byte[] png = renderer.render(signer.sign(t.getToken()), 320);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    @GetMapping("/api/v1/public/tickets/{token}/apple-wallet.pkpass")
    public ResponseEntity<byte[]> applePass(@PathVariable String token, HttpServletRequest http) {
        // Signing an archive is three DB reads, an RSA signature and a ZIP —
        // two orders of magnitude more expensive than the QR PNG next door, on
        // an endpoint whose only credential is a URL. Meter it, and meter it
        // BEFORE the lookup so token enumeration cannot spin the DB either.
        // Keyed on getRemoteAddr() (proxy-resolved via forward-headers-strategy),
        // never the client-controllable X-Forwarded-For.
        rateLimiter.consume("wallet-pass", "ip:" + http.getRemoteAddr());

        Ticket t = tickets.findByToken(token).orElseThrow(() -> ApiException.notFound("Ticket"));
        // Eligibility before the config gate, matching the Google endpoint below.
        // AppleWalletPassService.generatePass checks this too — it has to, it is
        // reachable from elsewhere — but it checks it AFTER its own configured
        // test, so with the wallet off a refunded ticket used to answer 503. The
        // plan's rule is one shared rule for both wallets, and 409 is the true
        // answer regardless of which env vars a deployment happens to carry.
        WalletEligibility.assertLive(t);
        if (!wallet.isConfigured()) {
            // Was a bare 503 with an empty body. Every other error in this API
            // is an ApiError envelope and imin-public's error handling reads
            // $.error.code, so an empty body read as a parse failure there.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Apple Wallet passes are not available");
        }
        byte[] pkpass = wallet.generatePass(t.getToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.CONTENT_TYPE, "application/vnd.apple.pkpass")
                // iOS Safari keys off the MIME type alone, but Android Chrome
                // and desktop browsers save the response under the last path
                // segment unless told otherwise — without this every download
                // lands as "apple-wallet.pkpass" with no relation to the ticket.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"imin-ticket-" + safeFilenamePart(t.getToken()) + ".pkpass\"")
                .body(pkpass);
    }

    /**
     * The Google half. Mirrors {@link #applePass} exactly — same credential (the
     * token in the path), same {@code wallet-pass} bucket, same 404/409/503
     * shapes — and differs only in what it hands back: a {@code 302} to Google's
     * save page rather than a file.
     *
     * <p><b>A redirect and not a URL in a JSON body</b>, because a save link is a
     * signed JWT with an {@code iat}: it cannot be built client-side, and minting
     * one on every ticket read would put an RSA signature and up to three calls
     * to Google on {@code GET /public/tickets/{token}} — the page every buyer
     * loads, wallet button or not.
     *
     * <p><b>The ordering of the three refusals is the whole design.</b> Unknown
     * token 404s before the config is consulted, so the status code cannot be
     * used to tell a real token from a fake one on a server where the wallet
     * happens to be off. A dead ticket 409s before that too: what a buyer learns
     * about their own ticket must not depend on our deployment state, and
     * "temporarily unavailable" would be a lie that invites a retry which can
     * never succeed. Only then does the gate answer 503.
     */
    @GetMapping("/api/v1/public/tickets/{token}/google-wallet")
    public ResponseEntity<Void> googleWalletSaveLink(@PathVariable String token,
                                                     HttpServletRequest http) {
        // Same bucket as the pkpass, and metered first for the same reason: this
        // is three DB reads plus an RSA signature plus up to three calls to
        // Google, on an endpoint whose only credential is a URL.
        rateLimiter.consume("wallet-pass", "ip:" + http.getRemoteAddr());

        Ticket t = tickets.findByToken(token).orElseThrow(() -> ApiException.notFound("Ticket"));
        WalletEligibility.assertLive(t);
        if (!googleWallet.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Google Wallet passes are not available");
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                // The Location carries a bearer artifact: anyone holding that JWT
                // can add the pass. A shared cache must never keep it, and a 302
                // is cacheable when a cache decides it is.
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.LOCATION, googleWallet.saveUrl(t.getToken()))
                .build();
    }

    /**
     * Tokens are URL-safe base64 without padding today ({@code A-Za-z0-9-_}),
     * so nothing here needs escaping — but a header built by concatenation is
     * one token-format change away from header injection or a path separator in
     * a filename, and this endpoint would not obviously break when that
     * happened. Not defensive coding for its own sake: the token is the
     * credential, and the format is decided in a different class.
     */
    private static String safeFilenamePart(String token) {
        return token == null ? "ticket" : token.replaceAll("[^A-Za-z0-9._-]", "");
    }
}

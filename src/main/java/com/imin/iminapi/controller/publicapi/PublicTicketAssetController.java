package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.ticket.AppleWalletPassService;
import com.imin.iminapi.service.ticket.QrImageRenderer;
import com.imin.iminapi.service.ticket.QrPayloadSigner;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-ticket asset endpoints — QR image and Apple Wallet pass. Token is the
 * only auth: it's a 24-byte random base64 string, unguessable, and arrived
 * only via the buyer's email or order page. {@code Cache-Control: private,
 * no-store} so a shared CDN cache hit can't serve one buyer's QR to another.
 */
@RestController
public class PublicTicketAssetController {

    private final TicketRepository tickets;
    private final QrPayloadSigner signer;
    private final QrImageRenderer renderer;
    private final AppleWalletPassService wallet;
    private final RateLimiter rateLimiter;

    public PublicTicketAssetController(TicketRepository tickets,
                                        QrPayloadSigner signer,
                                        QrImageRenderer renderer,
                                        AppleWalletPassService wallet,
                                        RateLimiter rateLimiter) {
        this.tickets = tickets;
        this.signer = signer;
        this.renderer = renderer;
        this.wallet = wallet;
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

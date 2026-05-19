package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.service.ticket.AppleWalletPassService;
import com.imin.iminapi.service.ticket.QrImageRenderer;
import com.imin.iminapi.service.ticket.QrPayloadSigner;
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

    public PublicTicketAssetController(TicketRepository tickets,
                                        QrPayloadSigner signer,
                                        QrImageRenderer renderer,
                                        AppleWalletPassService wallet) {
        this.tickets = tickets;
        this.signer = signer;
        this.renderer = renderer;
        this.wallet = wallet;
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
    public ResponseEntity<byte[]> applePass(@PathVariable String token) {
        Ticket t = tickets.findByToken(token).orElseThrow(() -> ApiException.notFound("Ticket"));
        if (!wallet.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        byte[] pkpass = wallet.generatePass(t.getToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.CONTENT_TYPE, "application/vnd.apple.pkpass")
                .body(pkpass);
    }
}

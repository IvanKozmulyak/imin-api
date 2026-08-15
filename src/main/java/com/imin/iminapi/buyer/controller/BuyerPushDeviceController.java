package com.imin.iminapi.buyer.controller;

import com.imin.iminapi.buyer.dto.BuyerPushDeviceRequests;
import com.imin.iminapi.buyer.security.BuyerPrincipal;
import com.imin.iminapi.buyer.security.CurrentBuyer;
import com.imin.iminapi.buyer.service.BuyerPushDeviceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Push device registration for the native app. Authenticated as a buyer, which
 * is why guests never appear here — they keep the email drop-alert path.
 */
@RestController
public class BuyerPushDeviceController {

    private static final String NO_STORE = "private, no-store";

    private final BuyerPushDeviceService devices;

    public BuyerPushDeviceController(BuyerPushDeviceService devices) {
        this.devices = devices;
    }

    @PostMapping("/api/v1/buyer/push-devices")
    public ResponseEntity<Void> register(@CurrentBuyer BuyerPrincipal buyer,
                                         @Valid @RequestBody BuyerPushDeviceRequests.Register req) {
        devices.register(buyer.accountId(), req.expoToken(), req.platform(),
                req.locale(), req.appVersion());
        return noContent();
    }

    /**
     * Always 204 — sign-out must never fail on a bookkeeping call, and an
     * unknown, already-revoked or someone else's token is answered identically
     * so the response cannot be used to probe who owns a device.
     *
     * <p><b>The token goes in the body, not the path.</b> An Expo token is
     * literally {@code ExponentPushToken[…]}, and {@code [} / {@code ]} are
     * gen-delims that are illegal unencoded in a path segment. Tomcat rejects
     * them with a 400 before Spring sees the request unless
     * {@code server.tomcat.relaxed-path-chars} is set, which it is not here —
     * and the WHATWG percent-encode set for paths does not include brackets, so
     * a URL built by string concatenation in the app sends them raw. MockMvc
     * builds its request object directly and never runs the container's URI
     * parser, so a {@code @DeleteMapping("/{expoToken}")} would test green here
     * and 400 on every real sign-out, leaving the device receiving pushes.
     *
     * <p>A body also keeps the token out of access logs.
     */
    @PostMapping("/api/v1/buyer/push-devices/revoke")
    public ResponseEntity<Void> revoke(@CurrentBuyer BuyerPrincipal buyer,
                                       @Valid @RequestBody BuyerPushDeviceRequests.Revoke req) {
        devices.revoke(buyer.accountId(), req.expoToken());
        return noContent();
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, NO_STORE).build();
    }
}

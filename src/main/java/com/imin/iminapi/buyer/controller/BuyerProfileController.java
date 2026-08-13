package com.imin.iminapi.buyer.controller;

import com.imin.iminapi.buyer.dto.BuyerMeResponse;
import com.imin.iminapi.buyer.dto.BuyerProfileRequests.IdentityResponse;
import com.imin.iminapi.buyer.dto.BuyerProfileRequests.PasswordChangeRequest;
import com.imin.iminapi.buyer.model.BuyerAccount;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.security.BuyerPrincipal;
import com.imin.iminapi.buyer.security.CurrentBuyer;
import com.imin.iminapi.buyer.service.BuyerProfileService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Profile writes — spec §4.3. Reads live on {@link BuyerMeController}; this is
 * the write half, kept separate so the read path stays the trivially auditable
 * one.
 */
@RestController
public class BuyerProfileController {

    private static final String NO_STORE = "private, no-store";

    private final BuyerProfileService service;
    private final BuyerAccountEmailRepository emails;

    public BuyerProfileController(BuyerProfileService service, BuyerAccountEmailRepository emails) {
        this.service = service;
        this.emails = emails;
    }

    /**
     * A partial patch of {@code displayName}, {@code city} and {@code locale}.
     *
     * <p>Taken as a raw map rather than a record: absent and explicitly-null
     * must mean different things ("leave it alone" vs "clear it"), and a record
     * deserializes both to null. See {@code BuyerProfileRequests}' javadoc.
     */
    @PatchMapping("/api/v1/buyer/me")
    public ResponseEntity<BuyerMeResponse> patch(@CurrentBuyer BuyerPrincipal buyer,
                                                 @RequestBody Map<String, Object> body) {
        BuyerAccount account = service.updateProfile(buyer.accountId(), body);
        var payload = BuyerMeResponse.of(
                account, emails.findByBuyerAccountIdOrderByCreatedAtAsc(account.getId()));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(payload);
    }

    @PostMapping("/api/v1/buyer/me/password")
    public ResponseEntity<Void> changePassword(@CurrentBuyer BuyerPrincipal buyer,
                                               @Valid @RequestBody PasswordChangeRequest body) {
        service.changePassword(buyer.accountId(), buyer.sessionId(),
                body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, NO_STORE).build();
    }

    @GetMapping("/api/v1/buyer/identities")
    public ResponseEntity<List<IdentityResponse>> identities(@CurrentBuyer BuyerPrincipal buyer) {
        List<IdentityResponse> rows = service.listIdentities(buyer.accountId()).stream()
                .map(i -> new IdentityResponse(i.getProvider(), i.getEmailAtLink(), i.getCreatedAt()))
                .toList();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(rows);
    }

    @DeleteMapping("/api/v1/buyer/identities/{provider}")
    public ResponseEntity<Void> unlink(@CurrentBuyer BuyerPrincipal buyer,
                                       @PathVariable String provider) {
        service.unlinkIdentity(buyer.accountId(), provider);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, NO_STORE).build();
    }
}

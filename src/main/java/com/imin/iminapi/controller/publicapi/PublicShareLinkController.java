package com.imin.iminapi.controller.publicapi;

import com.imin.iminapi.dto.publicapi.PublicShareLinkResponse;
import com.imin.iminapi.marketing.kit.ShareLinkService;
import com.imin.iminapi.security.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Resolves an app.imin.wtf/s/{code} short link; an unknown code and a non-public event are the same 404. */
@RestController
@RequestMapping("/api/v1/public/links")
public class PublicShareLinkController {

    private final ShareLinkService shareLinks;

    public PublicShareLinkController(ShareLinkService shareLinks) {
        this.shareLinks = shareLinks;
    }

    @GetMapping("/{code}")
    public ResponseEntity<PublicShareLinkResponse> resolve(@PathVariable String code) {
        ShareLinkService.Resolved r = shareLinks.resolve(code).orElseThrow(() -> ApiException.notFound("Link"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, s-maxage=300")
                .body(new PublicShareLinkResponse(r.eventId(), r.utmSource(), r.utmMedium(), r.utmCampaign()));
    }
}

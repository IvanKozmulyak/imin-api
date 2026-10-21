package com.imin.iminapi.marketing.controller;

import com.imin.iminapi.marketing.dto.StoryKitDto;
import com.imin.iminapi.marketing.dto.StoryKitRequests;
import com.imin.iminapi.marketing.kit.StoryKitCaptionService;
import com.imin.iminapi.marketing.kit.StoryKitService;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.RateLimiter;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** One event's Instagram kit; another org's event is a 404. Images stream through the API, so the dashboard CSP needs no storage origin. */
@RestController
@RequestMapping("/api/v1/events/{eventId}/story-kit")
public class StoryKitController {

    private final StoryKitService service;
    private final StoryKitCaptionService captions;
    private final RateLimiter rateLimiter;

    public StoryKitController(StoryKitService service, StoryKitCaptionService captions, RateLimiter rateLimiter) {
        this.service = service;
        this.captions = captions;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public StoryKitDto.State get(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID eventId) {
        return service.state(p, eventId);
    }

    @PostMapping
    public ResponseEntity<StoryKitDto.State> make(@AuthenticationPrincipal AuthPrincipal p,
                                                  @PathVariable UUID eventId,
                                                  @Valid @RequestBody StoryKitRequests.Create body) {
        rateLimiter.consume("story-kit", p.userId().toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.make(p, eventId, body.locale()));
    }

    @PostMapping("/caption")
    public StoryKitDto.Caption caption(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID eventId,
                                       @RequestBody StoryKitRequests.CaptionRequest body) {
        rateLimiter.consume("story-kit-caption", p.userId().toString());
        return captions.write(p, eventId, body.revision());
    }

    @GetMapping("/assets/{key}")
    public ResponseEntity<byte[]> asset(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID eventId,
                                        @PathVariable String key) {
        rateLimiter.consume("story-kit-download", p.userId().toString());
        byte[] bytes = service.assetBytes(p, eventId, key);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .body(bytes);
    }

    @GetMapping("/zip")
    public ResponseEntity<byte[]> zip(@AuthenticationPrincipal AuthPrincipal p, @PathVariable UUID eventId) {
        rateLimiter.consume("story-kit-download", p.userId().toString());
        StoryKitService.Zip zip = service.zip(p, eventId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(zip.fileName()).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(zip.bytes());
    }
}

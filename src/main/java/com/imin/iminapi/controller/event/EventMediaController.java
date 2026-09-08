package com.imin.iminapi.controller.event;

import com.imin.iminapi.dto.event.MediaUploadResponse;
import com.imin.iminapi.model.MediaKind;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.event.MediaUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events/{eventId}/media")
public class EventMediaController {

    private final MediaUploadService uploadService;

    public EventMediaController(MediaUploadService uploadService) {
        this.uploadService = uploadService;
    }

    /**
     * @param aiGenerated optional, POSTER only (AI Act Art.50). The Poster Studio
     *        uploads an image it generated through this same endpoint, and a file
     *        upload is otherwise indistinguishable from an organizer's own artwork
     *        — so the studio says so. Absent or false keeps the historic meaning
     *        of a multipart upload: the organizer's own asset.
     * @param rightsAttested <b>required for DJ_PHOTO</b> — that photo becomes an
     *        Ideogram character reference and is uploaded to the OpenRouter vision
     *        gate inside the finished poster, so somebody has to have claimed the
     *        right to put a third party's face there. Missing or false ⇒ 400
     *        {@code RIGHTS_ATTESTATION_REQUIRED}. Optional and recorded for other
     *        kinds.
     */
    @PostMapping(path = "/{kind}", consumes = "multipart/form-data")
    public MediaUploadResponse upload(@CurrentUser AuthPrincipal p,
                                      @PathVariable UUID eventId,
                                      @PathVariable String kind,
                                      @RequestPart("file") MultipartFile file,
                                      @RequestParam(name = "aiGenerated", required = false)
                                      Boolean aiGenerated,
                                      @RequestParam(name = "rightsAttested", required = false)
                                      Boolean rightsAttested) throws IOException {
        MediaKind k = kindOr404(kind);
        return uploadService.upload(p, eventId, k, file.getBytes(),
                file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename(),
                aiGenerated, rightsAttested);
    }

    @DeleteMapping("/{kind}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser AuthPrincipal p,
                       @PathVariable UUID eventId,
                       @PathVariable String kind) {
        uploadService.delete(p, eventId, kindOr404(kind));
    }

    private static MediaKind kindOr404(String kind) {
        try {
            return MediaKind.fromWire(kind);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Media kind");
        }
    }
}
